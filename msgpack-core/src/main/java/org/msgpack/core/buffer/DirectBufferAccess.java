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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.security.AccessController;
import java.security.PrivilegedAction;

import sun.nio.ch.DirectBuffer;

/**
 * Wraps the difference of access methods to DirectBuffers between Android and others.
 */
class DirectBufferAccess
{
    private DirectBufferAccess()
    {
    }

    enum DirectBufferConstructorType
    {
        ARGS_LONG_LONG,
        ARGS_LONG_INT_REF,
        ARGS_LONG_INT,
        ARGS_INT_INT,
        ARGS_MB_INT_INT
    }

    // For Java <=8, gets a sun.misc.Cleaner
    static Method mCleaner;
    static Method mClean;
    // For Java >=9, invokes a jdk.internal.ref.Cleaner
    static Method mInvokeCleaner;

    // TODO We should use MethodHandle for efficiency, but it is not available in JDK6
    static Constructor<?> byteBufferConstructor;
    static Class<?> directByteBufferClass;
    static DirectBufferConstructorType directBufferConstructorType;
    static Method memoryBlockWrapFromJni;

    static {
        try {
            final ByteBuffer direct = ByteBuffer.allocateDirect(1);
            // Find the hidden constructor for DirectByteBuffer
            directByteBufferClass = direct.getClass();
            Constructor<?> directByteBufferConstructor = null;
            DirectBufferConstructorType constructorType = null;
            Method mbWrap = null;
            try {
                // JDK21 DirectByteBuffer(long, long)
                directByteBufferConstructor = directByteBufferClass.getDeclaredConstructor(long.class, long.class);
                constructorType = DirectBufferConstructorType.ARGS_LONG_LONG;
            }
            catch (NoSuchMethodException e00) {
                try {
                    // TODO We should use MethodHandle for Java7, which can avoid the cost of boxing with JIT optimization
                    directByteBufferConstructor = directByteBufferClass.getDeclaredConstructor(long.class, int.class, Object.class);
                    constructorType = DirectBufferConstructorType.ARGS_LONG_INT_REF;
                }
                catch (NoSuchMethodException e0) {
                    try {
                        // https://android.googlesource.com/platform/libcore/+/master/luni/src/main/java/java/nio/DirectByteBuffer.java
                        // DirectByteBuffer(long address, int capacity)
                        directByteBufferConstructor = directByteBufferClass.getDeclaredConstructor(long.class, int.class);
                        constructorType = DirectBufferConstructorType.ARGS_LONG_INT;
                    }
                    catch (NoSuchMethodException e1) {
                        try {
                            directByteBufferConstructor = directByteBufferClass.getDeclaredConstructor(int.class, int.class);
                            constructorType = DirectBufferConstructorType.ARGS_INT_INT;
                        }
                        catch (NoSuchMethodException e2) {
                            Class<?> aClass = Class.forName("java.nio.MemoryBlock");
                            mbWrap = aClass.getDeclaredMethod("wrapFromJni", int.class, long.class);
                            mbWrap.setAccessible(true);
                            directByteBufferConstructor = directByteBufferClass.getDeclaredConstructor(aClass, int.class, int.class);
                            constructorType = DirectBufferConstructorType.ARGS_MB_INT_INT;
                        }
                    }
                }
            }

            byteBufferConstructor = directByteBufferConstructor;
            directBufferConstructorType = constructorType;
            memoryBlockWrapFromJni = mbWrap;

            if (byteBufferConstructor == null) {
                throw new RuntimeException("Constructor of DirectByteBuffer is not found");
            }

            try {
                byteBufferConstructor.setAccessible(true);
            }
            catch (RuntimeException e) {
                // This is a Java9+ exception, so we need to detect it without importing it for Java8 support
                if ("java.lang.reflect.InaccessibleObjectException".equals(e.getClass().getName())) {
                    byteBufferConstructor = null;
                }
                else {
                    throw e;
                }
            }

            if (MessageBuffer.javaVersion <= 8) {
                setupCleanerJava6(direct);
            }
            else {
                setupCleanerJava9(direct);
            }
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setupCleanerJava6(final ByteBuffer direct)
    {
        Object obj;
        obj = AccessController.doPrivileged(new PrivilegedAction<Object>()
        {
            @Override
            public Object run()
            {
                return getCleanerMethod(direct);
            }
        });
        if (obj instanceof Throwable) {
            throw new RuntimeException((Throwable) obj);
        }
        mCleaner = (Method) obj;

        obj = AccessController.doPrivileged(new PrivilegedAction<Object>()
        {
            @Override
            public Object run()
            {
                return getCleanMethod(direct, mCleaner);
            }
        });
        if (obj instanceof Throwable) {
            throw new RuntimeException((Throwable) obj);
        }
        mClean = (Method) obj;
    }

    private static void setupCleanerJava9(final ByteBuffer direct)
    {
    }

    /**
     * Checks if we have a usable {@link DirectByteBuffer#cleaner}.
     *
     * @param direct a direct buffer
     * @return the method or an error
     */
    private static Object getCleanerMethod(ByteBuffer direct)
    {
        try {
            Method m = direct.getClass().getDeclaredMethod("cleaner");
            m.setAccessible(true);
            m.invoke(direct);
            return m;
        }
        catch (NoSuchMethodException e) {
            return e;
        }
        catch (InvocationTargetException e) {
            return e;
        }
        catch (IllegalAccessException e) {
            return e;
        }
    }

    /**
     * Checks if we have a usable {@link sun.misc.Cleaner#clean}.
     *
     * @param direct a direct buffer
     * @param mCleaner the {@link DirectByteBuffer#cleaner} method
     * @return the method or null
     */
    private static Object getCleanMethod(ByteBuffer direct, Method mCleaner)
    {
        try {
            Method m = mCleaner.getReturnType().getDeclaredMethod("clean");
            Object c = mCleaner.invoke(direct);
            m.setAccessible(true);
            m.invoke(c);
            return m;
        }
        catch (NoSuchMethodException e) {
            return e;
        }
        catch (InvocationTargetException e) {
            return e;
        }
        catch (IllegalAccessException e) {
            return e;
        }
    }

    static long getAddress(Buffer buffer)
    {
        return ((DirectBuffer) buffer).address();
    }

    static void clean(Object base)
    {
        try {
        Object cleaner = mCleaner.invoke(base);
        mClean.invoke(cleaner);
        }
        catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    static boolean isDirectByteBufferInstance(Object s)
    {
        return directByteBufferClass.isInstance(s);
    }

    static ByteBuffer newByteBuffer(long address, int index, int length, ByteBuffer reference)
    {
        if (byteBufferConstructor == null) {
            throw new IllegalStateException("Can't create a new DirectByteBuffer. In JDK17+, two JVM options needs to be set: " +
                    "--add-opens=java.base/java.nio=ALL-UNNAMED and --add-opens=java.base/sun.nio.ch=ALL-UNNAMED");
        }
        try {
            switch (directBufferConstructorType) {
                case ARGS_LONG_LONG:
                    return (ByteBuffer) byteBufferConstructor.newInstance(address + index, (long) length);
                case ARGS_LONG_INT_REF:
                    return (ByteBuffer) byteBufferConstructor.newInstance(address + index, length, reference);
                case ARGS_LONG_INT:
                    return (ByteBuffer) byteBufferConstructor.newInstance(address + index, length);
                case ARGS_INT_INT:
                    return (ByteBuffer) byteBufferConstructor.newInstance((int) address + index, length);
                case ARGS_MB_INT_INT:
                    return (ByteBuffer) byteBufferConstructor.newInstance(
                            memoryBlockWrapFromJni.invoke(null, address + index, length),
                            length, 0);
                default:
                    throw new IllegalStateException("Unexpected value");
            }
        }
        catch (Throwable e) {
            // Convert checked exception to unchecked exception
            throw new RuntimeException(e);
        }
    }
}
