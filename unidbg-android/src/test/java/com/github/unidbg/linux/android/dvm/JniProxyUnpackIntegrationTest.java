package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.dvm.jni.ProxyClassFactory;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObject;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.memory.MemoryMap;
import com.github.unidbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;
import org.junit.Test;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.security.Provider;
import java.security.Security;
import java.util.HashMap;
import java.util.Map;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class JniProxyUnpackIntegrationTest {
    public static Object roundTrip(Object value) { return value; }

    public static final class ProviderProbe extends Provider {
        public static int calls;
        public String observed;
        public ProviderProbe() { super("binding-probe", 1.0, "metadata binding probe"); }
        public static int tick() { return ++calls; }
        public String echo(String value) { observed = value; return value; }
    }

    private static final class MetadataLoader extends ClassLoader {
        private final Map<String, Class<?>> copies = new HashMap<>();
        MetadataLoader(Provider[] providers) {
            super(JniProxyUnpackIntegrationTest.class.getClassLoader());
            for (Provider provider : providers) copies.put(provider.getClass().getName(), provider.getClass());
            copies.put(ProviderProbe.class.getName(), ProviderProbe.class);
        }
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> original = copies.get(name);
            if (original == null) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try (InputStream input = original.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        byte[] bytes = IOUtils.toByteArray(input);
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException failure) {
                        throw new ClassNotFoundException(name, failure);
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }

    @Test
    public void hostIdentityAndArrayWritesSurviveJniRoundTrip() throws Exception {
        for (boolean wide : new boolean[] {false, true}) {
            AndroidEmulatorBuilder builder = wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                VM vm = emulator.createDalvikVM();
                Provider[] expectedProviders = Security.getProviders();
                MetadataLoader metadata = new MetadataLoader(expectedProviders);
                vm.setDvmClassFactory(new ProxyClassFactory(metadata));
                Pointer env = vm.getJNIEnv(), table = env.getPointer(0);
                int width = wide ? 8 : 4;
                MemoryBlock block = emulator.getMemory().malloc(512, true);
                try {
                    Pointer data = block.getPointer();
                    data.setString(0, "roundTrip");
                    data.setString(32, "(Ljava/lang/Object;)Ljava/lang/Object;");
                    int type = vm.addLocalObject(vm.resolveClass(getClass().getName().replace('.', '/')));
                    int method = invoke(emulator, table, width, 113, env, type, data, data.share(32)).intValue();
                    Object shared = new Object();
                    Object[] values = {new String[] {"first", null}, new String[0],
                            new Object[] {shared, shared, null, String.class},
                            new Class<?>[] {String.class, Object.class}, String.class,
                            new byte[] {1, 2, 3}, new boolean[] {true, false},
                            new char[] {0, '\ufedc'}, new long[] {Long.MIN_VALUE, 0x1234567887654321L}, null};
                    for (Object expected : values) {
                        int input = vm.addLocalObject(ProxyDvmObject.createObject(vm, expected));
                        int output = invoke(emulator, table, width, 114, env, type, method, input).intValue();
                        assertSame(expected, ProxyDvmObject.unpack(vm.getObject(output)));
                    }

                    Object[] original = {shared, shared};
                    int array = vm.addLocalObject(ProxyDvmObject.createObject(vm, original));
                    int first = invoke(emulator, table, width, 173, env, array, 0).intValue();
                    int second = invoke(emulator, table, width, 173, env, array, 1).intValue();
                    assertSame(shared, ProxyDvmObject.unpack(vm.getObject(first)));
                    assertSame(vm.getObject(first), vm.getObject(second));
                    int classValue = vm.addLocalObject(ProxyDvmObject.createObject(vm, String.class));
                    invoke(emulator, table, width, 174, env, array, 0, classValue);
                    assertSame(String.class, original[0]);
                    assertSame(shared, original[1]);
                    assertSame(original, ProxyDvmObject.unpack(vm.getObject(array)));
                    primitiveArrays(emulator, vm, env, table, width, data);
                    classes(emulator, vm, env, table, width, data, type, method);
                    providers(emulator, vm, env, table, width, data, metadata, expectedProviders);

                } finally {
                    block.free();
                }
            }
        }
    }

    private static void primitiveArrays(AndroidEmulator emulator, VM vm, Pointer env, Pointer table, int width, Pointer data) {
        Object[] initial = {new boolean[] {false, true, false}, new char[] {1, '\ufedc', 'A'},
                new long[] {Long.MIN_VALUE, 0x1234567887654321L, -1}};
        Object[] regions = {new boolean[] {false, false, false}, new char[] {1, '\u8001', 'A'},
                new long[] {Long.MIN_VALUE, Long.MAX_VALUE, -1}};
        Object[] committed = {new boolean[] {true, false, true}, new char[] {'A', 'B', '\uffff'},
                new long[] {0x123456789abcdefL, -1, 0}};
        int[] offsets = {0, 2, 5};
        String[] descriptors = {"[Z", "[C", "[J"};
        for (int kind = 0; kind < initial.length; kind++) {
            int offset = offsets[kind];
            int handle = vm.addGlobalObject(ProxyDvmObject.createObject(vm, initial[kind]));
            assertEquals(3, invoke(emulator, table, width, 171, env, handle).intValue());
            data.setString(0, descriptors[kind]);
            int clazz = invoke(emulator, table, width, 6, env, data).intValue();
            assertEquals(VM.JNI_TRUE, invoke(emulator, table, width, 32, env, handle, clazz).intValue());
            int actualClass = invoke(emulator, table, width, 31, env, handle).intValue();
            assertSame(initial[kind].getClass(), ProxyDvmObject.unpack(vm.getObject(actualClass)));
            Pointer buffer = data.share(128), isCopy = data.share(96);
            byte[] expectedRegion = bytes(initial[kind]);
            invoke(emulator, table, width, 199 + offset, env, handle, 0, 3, buffer);
            assertArrayEquals(expectedRegion, buffer.getByteArray(0, expectedRegion.length));
            byte[] changed = bytes(regions[kind]);
            buffer.write(0, changed, 0, changed.length);
            int elementSize = changed.length / 3;
            invoke(emulator, table, width, 207 + offset, env, handle, 1, 1, buffer.share(elementSize));
            contents(regions[kind], initial[kind]);
            invoke(emulator, table, width, 207 + offset, env, handle, 2, 2, buffer);
            int pending = invoke(emulator, table, width, 15, env).intValue();
            assertEquals(ArrayIndexOutOfBoundsException.class, ProxyDvmObject.unpack(vm.getObject(pending)).getClass());
            contents(regions[kind], initial[kind]);
            invoke(emulator, table, width, 17, env);

            long[] maps = memoryMaps(emulator);
            isCopy.setInt(0, 0x5a5a5a5a);
            Pointer first = pointer(emulator, invoke(emulator, table, width, 183 + offset, env, handle, isCopy));
            assertArrayEquals(new byte[] {1, 0x5a, 0x5a, 0x5a}, isCopy.getByteArray(0, 4));
            Pointer second = pointer(emulator, invoke(emulator, table, width, 183 + offset, env, handle, 0));
            byte[] edits = bytes(committed[kind]);
            first.write(0, edits, 0, edits.length);
            assertArrayEquals(changed, second.getByteArray(0, changed.length));
            contents(regions[kind], initial[kind]);
            invoke(emulator, table, width, 191 + offset, env, handle, first, VM.JNI_COMMIT);
            contents(committed[kind], initial[kind]);
            assertSame(initial[kind], ProxyDvmObject.unpack(vm.getObject(handle)));
            first.write(0, changed, 0, changed.length);
            invoke(emulator, table, width, 191 + offset, env, handle, first, 0);
            contents(regions[kind], initial[kind]);
            second.write(0, edits, 0, edits.length);
            invoke(emulator, table, width, 191 + offset, env, handle, second, VM.JNI_ABORT);
            contents(regions[kind], initial[kind]);
            assertArrayEquals(maps, memoryMaps(emulator));

            Pointer critical = pointer(emulator, invoke(emulator, table, width, 222, env, handle, 0));
            critical.write(0, edits, 0, edits.length);
            invoke(emulator, table, width, 223, env, handle, critical, 0);
            contents(committed[kind], initial[kind]);
            assertArrayEquals(maps, memoryMaps(emulator));
            Pointer retainedFirst = pointer(emulator, invoke(emulator, table, width, 183 + offset, env, handle, 0));
            Pointer retainedSecond = pointer(emulator, invoke(emulator, table, width, 183 + offset, env, handle, 0));
            invoke(emulator, table, width, 22, env, handle);
            int reacquired = vm.addGlobalObject(ProxyDvmObject.createObject(vm, initial[kind]));
            assertArrayEquals(edits, retainedFirst.getByteArray(0, edits.length));
            assertArrayEquals(edits, retainedSecond.getByteArray(0, edits.length));
            invoke(emulator, table, width, 191 + offset, env, reacquired, retainedFirst, VM.JNI_ABORT);
            invoke(emulator, table, width, 191 + offset, env, reacquired, retainedSecond, VM.JNI_ABORT);
            invoke(emulator, table, width, 22, env, reacquired);
            assertArrayEquals(maps, memoryMaps(emulator));

            int empty = invoke(emulator, table, width, 175 + offset, env, 0).intValue();
            Object emptyValue = ProxyDvmObject.unpack(vm.getObject(empty));
            assertEquals(initial[kind].getClass(), emptyValue.getClass());
            contents(java.lang.reflect.Array.newInstance(initial[kind].getClass().getComponentType(), 0), emptyValue);
            assertEquals(0, invoke(emulator, table, width, 171, env, empty).intValue());
            invoke(emulator, table, width, 199 + offset, env, empty, 0, 0, 0);
            invoke(emulator, table, width, 207 + offset, env, empty, 0, 0, 0);
            Pointer emptyCopy = pointer(emulator, invoke(emulator, table, width, 183 + offset, env, empty, 0));
            invoke(emulator, table, width, 191 + offset, env, empty, emptyCopy, VM.JNI_ABORT);
            assertArrayEquals(maps, memoryMaps(emulator));
            int created = invoke(emulator, table, width, 175 + offset, env, 3).intValue();
            buffer.write(0, edits, 0, edits.length);
            invoke(emulator, table, width, 207 + offset, env, created, 0, 3, buffer);
            contents(committed[kind], ProxyDvmObject.unpack(vm.getObject(created)));
            invoke(emulator, table, width, 175 + offset, env, -1);
            pending = invoke(emulator, table, width, 15, env).intValue();
            assertEquals(NegativeArraySizeException.class, ProxyDvmObject.unpack(vm.getObject(pending)).getClass());
            invoke(emulator, table, width, 17, env);
        }
    }

    private static void classes(AndroidEmulator emulator, VM vm, Pointer env, Pointer table, int width,
                                Pointer data, int roundTripClass, int roundTripMethod) {
        Class<?>[] expectedClasses = {boolean.class, byte.class, char.class, short.class, int.class,
                long.class, float.class, double.class, void.class, boolean[].class, char[].class,
                long[].class, long[][].class, String[].class, String.class};
        for (Class<?> expected : expectedClasses) {
            int input = vm.addLocalObject(ProxyDvmObject.createObject(vm, expected));
            int result = invoke(emulator, table, width, 114, env, roundTripClass, roundTripMethod, input).intValue();
            assertSame(expected, ProxyDvmObject.unpack(vm.getObject(result)));
        }
        int integers = vm.addLocalObject(vm.resolveClass("java/lang/Integer"));
        data.setString(0, "TYPE");
        data.setString(64, "Ljava/lang/Class;");
        int field = invoke(emulator, table, width, 144, env, integers, data, data.share(64)).intValue();
        int primitiveClass = invoke(emulator, table, width, 145, env, integers, field).intValue();
        assertSame(int.class, ProxyDvmObject.unpack(vm.getObject(primitiveClass)));
        int classType = vm.addLocalObject(vm.resolveClass("java/lang/Class"));
        data.setString(0, "forName");
        data.setString(64, "(Ljava/lang/String;)Ljava/lang/Class;");
        int forName = invoke(emulator, table, width, 113, env, classType, data, data.share(64)).intValue();
        Class<?>[] lookups = {long[].class, long[][].class, String[].class, String.class};
        for (Class<?> expected : lookups) {
            int name = vm.addLocalObject(ProxyDvmObject.createObject(vm, expected.getName()));
            int result = invoke(emulator, table, width, 114, env, classType, forName, name).intValue();
            assertSame(expected, ProxyDvmObject.unpack(vm.getObject(result)));
        }
    }

    private static void providers(AndroidEmulator emulator, VM vm, Pointer env, Pointer table, int width,
                                  Pointer data, MetadataLoader metadata, Provider[] expected) throws ClassNotFoundException {
        int security = vm.addLocalObject(vm.resolveClass("java/security/Security"));
        data.setString(0, "getProviders");
        data.setString(64, "()[Ljava/security/Provider;");
        int getProviders = invoke(emulator, table, width, 113, env, security, data, data.share(64)).intValue();
        int result = invoke(emulator, table, width, 116, env, security, getProviders, data.share(128)).intValue();
        Provider[] actual = (Provider[]) ProxyDvmObject.unpack(vm.getObject(result));
        assertEquals(expected.length, invoke(emulator, table, width, 171, env, result).intValue());
        for (int index = 0; index < expected.length; index++) {
            assertSame(expected[index], actual[index]);
            int provider = invoke(emulator, table, width, 173, env, result, index).intValue();
            assertSame(expected[index], ProxyDvmObject.unpack(vm.getObject(provider)));
            int type = invoke(emulator, table, width, 31, env, provider).intValue();
            assertSame(expected[index].getClass(), ProxyDvmObject.unpack(vm.getObject(type)));
            data.setString(0, "getName");
            data.setString(64, "()Ljava/lang/String;");
            int getName = invoke(emulator, table, width, 33, env, type, data, data.share(64)).intValue();
            int name = invoke(emulator, table, width, 36, env, provider, getName, data.share(128)).intValue();
            assertEquals(expected[index].getName(), ProxyDvmObject.unpack(vm.getObject(name)));
            data.setString(0, "getInfo");
            int getInfo = invoke(emulator, table, width, 33, env, type, data, data.share(64)).intValue();
            int info = invoke(emulator, table, width, 36, env, provider, getInfo, data.share(128)).intValue();
            assertEquals(expected[index].getInfo(), ProxyDvmObject.unpack(vm.getObject(info)));
        }

        ProviderProbe.calls = 0;
        int probeClass = vm.addLocalObject(vm.resolveClass(ProviderProbe.class.getName().replace('.', '/')));
        data.setString(0, "tick");
        data.setString(64, "()I");
        int tick = invoke(emulator, table, width, 113, env, probeClass, data, data.share(64)).intValue();
        assertEquals(1, invoke(emulator, table, width, 131, env, probeClass, tick, data.share(128)).intValue());
        data.setString(0, "calls");
        data.setString(64, "I");
        int calls = invoke(emulator, table, width, 144, env, probeClass, data, data.share(64)).intValue();
        assertEquals(1, invoke(emulator, table, width, 150, env, probeClass, calls).intValue());
        assertEquals(0, ProviderProbe.calls);

        ProviderProbe probe = new ProviderProbe();
        int receiver = vm.addLocalObject(ProxyDvmObject.createObject(vm, probe));
        assertSame(ProviderProbe.class, ProxyDvmObject.unpack(vm.getObject(probeClass)));
        assertEquals(0, invoke(emulator, table, width, 150, env, probeClass, calls).intValue());
        assertEquals(1, invoke(emulator, table, width, 131, env, probeClass, tick, data.share(128)).intValue());
        assertEquals(1, ProviderProbe.calls);
        data.setString(0, "echo");
        data.setString(64, "(Ljava/lang/String;)Ljava/lang/String;");
        int echo = invoke(emulator, table, width, 33, env, probeClass, data, data.share(64)).intValue();
        String message = "actual-provider-instance";
        int argument = vm.addLocalObject(ProxyDvmObject.createObject(vm, message));
        data.setLong(128, argument & 0xffffffffL);
        int reply = invoke(emulator, table, width, 36, env, receiver, echo, data.share(128)).intValue();
        assertEquals(message, ProxyDvmObject.unpack(vm.getObject(reply)));
        assertEquals(message, probe.observed);
        data.setString(0, "observed");
        data.setString(64, "Ljava/lang/String;");
        int observed = invoke(emulator, table, width, 94, env, probeClass, data, data.share(64)).intValue();
        int value = invoke(emulator, table, width, 95, env, receiver, observed).intValue();
        assertEquals(probe.observed, ProxyDvmObject.unpack(vm.getObject(value)));
        Class<?> otherActual = metadata.loadClass(ProviderProbe.class.getName());
        assertThrows(IllegalStateException.class, () -> ProxyDvmObject.createObject(vm, otherActual));
        assertSame(ProviderProbe.class, ProxyDvmObject.unpack(vm.getObject(probeClass)));


    }

    private static Pointer pointer(AndroidEmulator emulator, Number address) {
        return UnidbgPointer.pointer(emulator, emulator.is32Bit() ? address.longValue() & 0xffffffffL : address.longValue());
    }

    private static byte[] bytes(Object array) {
        int size = java.lang.reflect.Array.getLength(array);
        int width = array instanceof boolean[] ? 1 : array instanceof char[] ? 2 : 8;
        ByteBuffer buffer = ByteBuffer.allocate(size * width).order(ByteOrder.LITTLE_ENDIAN);
        if (array instanceof boolean[]) for (boolean value : (boolean[]) array) buffer.put((byte) (value ? 1 : 0));
        else if (array instanceof char[]) for (char value : (char[]) array) buffer.putChar(value);
        else for (long value : (long[]) array) buffer.putLong(value);
        return buffer.array();
    }

    private static void contents(Object expected, Object actual) {
        if (expected instanceof boolean[]) assertArrayEquals((boolean[]) expected, (boolean[]) actual);
        else if (expected instanceof char[]) assertArrayEquals((char[]) expected, (char[]) actual);
        else assertArrayEquals((long[]) expected, (long[]) actual);
    }

    private static long[] memoryMaps(AndroidEmulator emulator) {
        List<MemoryMap> maps = new ArrayList<>(emulator.getMemory().getMemoryMap());
        maps.sort(Comparator.comparingLong(map -> map.base));
        long[] result = new long[maps.size() * 3];
        for (int i = 0; i < maps.size(); i++) {
            result[i * 3] = maps.get(i).base;
            result[i * 3 + 1] = maps.get(i).size;
            result[i * 3 + 2] = maps.get(i).prot;
        }
        return result;
    }

    private static Number invoke(AndroidEmulator emulator, Pointer table, int width, int index, Object... args) {
        return Module.emulateFunction(emulator, UnidbgPointer.nativeValue(table.getPointer((long) index * width)), args);
    }
}
