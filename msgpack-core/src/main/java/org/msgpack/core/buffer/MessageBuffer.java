//
// MessagePack for Java
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//        http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//
package org.msgpack.core.buffer;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

import static org.msgpack.core.Preconditions.checkArgument;

/**
 * MessageBuffer class is an abstraction of memory with fast methods to serialize and deserialize primitive values
 * to/from the memory. All MessageBuffer implementations ensure short/int/float/long/double values are written in
 * big-endian order.
 * <p>
 * Applications can allocate a new buffer using {@link #allocate(int)} method, or wrap an byte array or ByteBuffer
 * using {@link #wrap(byte[], int, int)} methods. {@link #wrap(ByteBuffer)} method supports both direct buffers and
 * array-backed buffers.
 * <p>
 * MessageBuffer class itself is optimized for little-endian CPU archtectures so that JVM (HotSpot) can take advantage
 * of the fastest JIT format which skips TypeProfile checking. To ensure this performance, applications must not import
 * unnecessary classes such as MessagePackBE. On big-endian CPU archtectures, it automatically uses a subclass that
 * includes TypeProfile overhead but still faster than stndard ByteBuffer class. On JVMs older than Java 7 and JVMs
 * without Unsafe API (such as Android), implementation falls back to an universal implementation that uses ByteBuffer
 * internally.
 */
public class MessageBuffer
{
    static final int javaVersion = getJavaVersion();
    private final ByteBuffer wrap;

    /**
     * The offset from the object memory header to its byte array data
     */
    static final int ARRAY_BYTE_BASE_OFFSET = 16;

    private static int getJavaVersion()
    {
        String javaVersion = System.getProperty("java.specification.version", "");
        int dotPos = javaVersion.indexOf('.');
        if (dotPos != -1) {
            try {
                int major = Integer.parseInt(javaVersion.substring(0, dotPos));
                int minor = Integer.parseInt(javaVersion.substring(dotPos + 1));
                return major > 1 ? major : minor;
            }
            catch (NumberFormatException e) {
                e.printStackTrace(System.err);
            }
        }
        else {
            try {
                return Integer.parseInt(javaVersion);
            }
            catch (NumberFormatException e) {
                e.printStackTrace(System.err);
            }
        }
        return 6;
    }

    /**
     * Base object for resolving the relative address of the raw byte array.
     * If base == null, the address value is a raw memory address
     */
    protected final Object base;

    /**
     * Head address of the underlying memory. If base is null, the address is a direct memory address, and if not,
     * it is the relative address within an array object (base)
     */
    protected final long address;

    /**
     * Size of the underlying memory
     */
    protected final int size;

    /**
     * Reference is used to hold a reference to an object that holds the underlying memory so that it cannot be
     * released by the garbage collector.
     */
    protected final ByteBuffer reference;

    /**
     * Allocates a new MessageBuffer backed by a byte array.
     *
     * @throws IllegalArgumentException If the capacity is a negative integer
     *
     */
    public static MessageBuffer allocate(int size)
    {
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        return wrap(new byte[size]);
    }

    /**
     * Wraps a byte array into a MessageBuffer.
     *
     * The new MessageBuffer will be backed by the given byte array. Modifications to the new MessageBuffer will cause the byte array to be modified and vice versa.
     *
     * The new buffer's size will be array.length. hasArray() will return true.
     *
     * @param array the byte array that will gack this MessageBuffer
     * @return a new MessageBuffer that wraps the given byte array
     *
     */
    public static MessageBuffer wrap(byte[] array)
    {
        return MessageBuffer.wrap(array, 0, array.length);
    }

    /**
     * Wraps a byte array into a MessageBuffer.
     *
     * The new MessageBuffer will be backed by the given byte array. Modifications to the new MessageBuffer will cause the byte array to be modified and vice versa.
     *
     * The new buffer's size will be length. hasArray() will return true.
     *
     * @param array the byte array that will gack this MessageBuffer
     * @param offset The offset of the subarray to be used; must be non-negative and no larger than array.length
     * @param length The length of the subarray to be used; must be non-negative and no larger than array.length - offset
     * @return a new MessageBuffer that wraps the given byte array
     *
     */
    public static MessageBuffer wrap(byte[] array, int offset, int length)
    {
        return new MessageBuffer(array, offset, length);
    }

    /**
     * Wraps a ByteBuffer into a MessageBuffer.
     *
     * The new MessageBuffer will be backed by the given byte buffer. Modifications to the new MessageBuffer will cause the byte buffer to be modified and vice versa. However, change of position, limit, or mark of given byte buffer doesn't affect MessageBuffer.
     *
     * The new buffer's size will be bb.remaining(). hasArray() will return the same result with bb.hasArray().
     *
     * @param bb the byte buffer that will gack this MessageBuffer
     * @throws IllegalArgumentException given byte buffer returns false both from hasArray() and isDirect()
     * @throws UnsupportedOperationException given byte buffer is a direct buffer and this platform doesn't support Unsafe API
     * @return a new MessageBuffer that wraps the given byte array
     *
     */
    public static MessageBuffer wrap(ByteBuffer bb)
    {
        return new MessageBuffer(bb);
    }

    /**
     * Creates a new MessageBuffer instance
     *
     * @param constructor A MessageBuffer constructor
     * @return new MessageBuffer instance
     */

    public static void releaseBuffer(MessageBuffer buffer)
    {
    }

    /**
     * Create a MessageBuffer instance from an java heap array
     *
     * @param arr
     * @param offset
     * @param length
     */
    MessageBuffer(byte[] arr, int offset, int length)
    {
        this.base = arr;  // non-null is already checked at newMessageBuffer
        this.address = ARRAY_BYTE_BASE_OFFSET + offset;
        this.size = length;
        this.reference = null;
        this.wrap = ByteBuffer.wrap(arr, offset, length).slice();
    }

    /**
     * Create a MessageBuffer instance from a given ByteBuffer instance
     *
     * @param bb
     */
    MessageBuffer(ByteBuffer bb)
    {
        this.wrap = bb.slice();
        if (bb.isDirect()) {
            // MessageBufferU overrides almost all methods, only field 'size' is used.
            this.base = null;
            this.address = 0;
            this.size = bb.remaining();
            this.reference = null;
            return;
        }
        else if (bb.hasArray()) {
            this.base = bb.array();
            this.address = ARRAY_BYTE_BASE_OFFSET + bb.arrayOffset() + bb.position();
            this.size = bb.remaining();
            this.reference = null;
        }
        else {
            throw new IllegalArgumentException("Only the array-backed ByteBuffer or DirectBuffer is supported");
        }
    }

    protected MessageBuffer(Object base, long address, int length, ByteBuffer wrap)
    {
        this.base = base;
        this.address = address;
        this.size = length;
        this.reference = null;
        this.wrap = wrap;
    }

    /**
     * Gets the size of the buffer.
     *
     * MessageBuffer doesn't have limit unlike ByteBuffer. Instead, you can use {@link #slice(int, int)} to get a
     * part of the buffer.
     *
     * @return number of bytes
     */
    public int size()
    {
        return size;
    }

    public MessageBuffer slice(int offset, int length)
    {
        if (offset == 0 && length == size()) {
            return this;
        }
        else {
            checkArgument(offset + length <= size());
            try {
                wrap.position(offset);
                wrap.limit(offset + length);
                return new MessageBuffer(base, address + offset, length, wrap.slice());
            }
            finally {
                resetBufferPosition();
            }
        }
    }

    public byte getByte(int index)
    {
        return wrap.get(index);
    }

    public boolean getBoolean(int index)
    {
        return wrap.get(index) != 0;
    }

    public short getShort(int index)
    {
        return wrap.getShort(index);
    }

    /**
     * Read a big-endian int value at the specified index
     *
     * @param index
     * @return
     */
    public int getInt(int index)
    {
        return wrap.getInt(index);
    }

    public float getFloat(int index)
    {
        return Float.intBitsToFloat(getInt(index));
    }

    public long getLong(int index)
    {
        return wrap.getLong(index);
    }

    public double getDouble(int index)
    {
        return Double.longBitsToDouble(getLong(index));
    }

    public void getBytes(int index, byte[] dst, int dstOffset, int length)
    {
        try {
            wrap.position(index);
            wrap.get(dst, dstOffset, length);
        }
        finally {
            resetBufferPosition();
        }
    }

    public void getBytes(int index, int len, ByteBuffer dst)
    {
        if (dst.remaining() < len) {
            throw new BufferOverflowException();
        }
        ByteBuffer src = sliceAsByteBuffer(index, len);
        dst.put(src);
    }

    public void putByte(int index, byte v)
    {
        wrap.put(index, v);
    }

    public void putBoolean(int index, boolean v)
    {
        wrap.put(index, v ? (byte) 1 : (byte) 0);
    }

    public void putShort(int index, short v)
    {
        wrap.putShort(index, v);
    }

    /**
     * Write a big-endian integer value to the memory
     *
     * @param index
     * @param v
     */
    public void putInt(int index, int v)
    {
        wrap.putInt(index, v);
    }

    public void putFloat(int index, float v)
    {
        putInt(index, Float.floatToRawIntBits(v));
    }

    public void putLong(int index, long l)
    {
        wrap.putLong(index, l);
    }

    public void putDouble(int index, double v)
    {
        putLong(index, Double.doubleToRawLongBits(v));
    }

    public void putBytes(int index, byte[] src, int srcOffset, int length)
    {
        try {
            wrap.position(index);
            wrap.put(src, srcOffset, length);
        }
        finally {
            resetBufferPosition();
        }
    }

    public void putByteBuffer(int index, ByteBuffer src, int len)
    {
        assert (len <= src.remaining());

        if (src.hasArray()) {
            putBytes(index, src.array(), src.position() + src.arrayOffset(), len);
            src.position(src.position() + len);
        }
        else {
            int prevSrcLimit = src.limit();
            try {
                src.limit(src.position() + len);
                wrap.position(index);
                wrap.put(src);
            }
            finally {
                src.limit(prevSrcLimit);
            }
        }
    }

    public void putMessageBuffer(int index, MessageBuffer src, int srcOffset, int len)
    {
        putByteBuffer(index, src.sliceAsByteBuffer(srcOffset, len), len);
    }

    /**
     * Create a ByteBuffer view of the range [index, index+length) of this memory
     *
     * @param index
     * @param length
     * @return
     */
    public ByteBuffer sliceAsByteBuffer(int index, int length)
    {
            return ByteBuffer.wrap((byte[]) base, (int) ((address - ARRAY_BYTE_BASE_OFFSET) + index), length);
    }

    /**
     * Get a ByteBuffer view of this buffer
     *
     * @return
     */
    public ByteBuffer sliceAsByteBuffer()
    {
        return sliceAsByteBuffer(0, size());
    }

    public boolean hasArray()
    {
        return base != null;
    }

    /**
     * Get a copy of this buffer
     *
     * @return
     */
    public byte[] toByteArray()
    {
        byte[] b = new byte[size()];
        getBytes(0, b, 0, b.length);
        return b;
    }

    public byte[] array()
    {
        return (byte[]) base;
    }

    public int arrayOffset()
    {
        return (int) address - ARRAY_BYTE_BASE_OFFSET;
    }

    /**
     * Copy this buffer contents to another MessageBuffer
     *
     * @param index
     * @param dst
     * @param offset
     * @param length
     */
    public void copyTo(int index, MessageBuffer dst, int offset, int length)
    {
        try {
            wrap.position(index);
            dst.putByteBuffer(offset, wrap, length);
        }
        finally {
            resetBufferPosition();
        }
    }

    public String toHexString(int offset, int length)
    {
        StringBuilder s = new StringBuilder();
        for (int i = offset; i < length; ++i) {
            if (i != offset) {
                s.append(" ");
            }
            s.append(String.format("%02x", getByte(i)));
        }
        return s.toString();
    }

    private void resetBufferPosition()
    {
        wrap.position(0);
        wrap.limit(size);
    }
}
