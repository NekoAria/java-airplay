package wtf.nanoka.airplay.player.gstreamer;

import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.ptr.PointerByReference;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Enumerates the same DXGI adapter order consumed by the D3D video sinks. */
@Slf4j
public final class GpuAdapterScanner {

    private static final int DXGI_ERROR_NOT_FOUND = 0x887A0002;
    private static final int ENUM_ADAPTERS1_VTABLE_INDEX = 12;
    private static final int GET_DESC1_VTABLE_INDEX = 10;
    private static final int RELEASE_VTABLE_INDEX = 2;
    private static final Guid.GUID IID_IDXGI_FACTORY1 =
            new Guid.GUID("770aae78-f26f-4dba-a829-253c83d1b387");

    private GpuAdapterScanner() {
    }

    public static List<GpuAdapter> scan() {
        if (!isWindows()) {
            return List.of();
        }

        Pointer factory = Pointer.NULL;
        try {
            var dxgi = Native.load("dxgi", DxgiLibrary.class);
            IID_IDXGI_FACTORY1.write();
            var factoryOut = new PointerByReference();
            int result = dxgi.CreateDXGIFactory1(IID_IDXGI_FACTORY1.getPointer(), factoryOut);
            if (!succeeded(result) || factoryOut.getValue() == null) {
                log.warn("Unable to create a DXGI factory (HRESULT=0x{})", Integer.toHexString(result));
                return List.of();
            }
            factory = factoryOut.getValue();

            List<GpuAdapter> adapters = new ArrayList<>();
            for (int index = 0; ; index++) {
                Pointer adapter = Pointer.NULL;
                try {
                    var adapterOut = new PointerByReference();
                    result = invokeVtable(factory, ENUM_ADAPTERS1_VTABLE_INDEX, index, adapterOut);
                    if (result == DXGI_ERROR_NOT_FOUND) {
                        break;
                    }
                    if (!succeeded(result) || adapterOut.getValue() == null) {
                        log.debug("DXGI adapter enumeration stopped at index {} (HRESULT=0x{})",
                                index, Integer.toHexString(result));
                        break;
                    }
                    adapter = adapterOut.getValue();
                    var description = new DxgiAdapterDesc1();
                    int descriptionResult = invokeVtable(adapter, GET_DESC1_VTABLE_INDEX, description.getPointer());
                    if (!succeeded(descriptionResult)) {
                        log.debug("Unable to read DXGI adapter {} description (HRESULT=0x{})",
                                index, Integer.toHexString(descriptionResult));
                        continue;
                    }
                    description.read();
                    adapters.add(new GpuAdapter(index,
                            description.displayName(),
                            description.vendorId,
                            description.dedicatedVideoMemory,
                            description.adapterLuid,
                            description.flags));
                } finally {
                    release(adapter);
                }
            }
            return Collections.unmodifiableList(adapters);
        } catch (UnsatisfiedLinkError | IllegalArgumentException e) {
            log.warn("Unable to enumerate DXGI GPU adapters: {}", e.getMessage());
            return List.of();
        } finally {
            release(factory);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    private static boolean succeeded(int hresult) {
        return hresult >= 0;
    }

    private static int invokeVtable(Pointer object, int methodIndex, Object... arguments) {
        Pointer vtable = object.getPointer(0);
        Function method = Function.getFunction(vtable.getPointer((long) methodIndex * Native.POINTER_SIZE));
        Object[] callArguments = new Object[arguments.length + 1];
        callArguments[0] = object;
        System.arraycopy(arguments, 0, callArguments, 1, arguments.length);
        return method.invokeInt(callArguments);
    }

    private static void release(Pointer object) {
        if (object != null && object != Pointer.NULL && Pointer.nativeValue(object) != 0) {
            invokeVtable(object, RELEASE_VTABLE_INDEX);
        }
    }

    private interface DxgiLibrary extends Library {
        int CreateDXGIFactory1(Pointer riid, PointerByReference factory);
    }

    /** DXGI_ADAPTER_DESC1, using bytes for the UTF-16 description field. */
    public static final class DxgiAdapterDesc1 extends Structure {
        public byte[] description = new byte[256];
        public int vendorId;
        public int deviceId;
        public int subSystemId;
        public int revision;
        public long dedicatedVideoMemory;
        public long dedicatedSystemMemory;
        public long sharedSystemMemory;
        public long adapterLuid;
        public int flags;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("description", "vendorId", "deviceId", "subSystemId", "revision",
                    "dedicatedVideoMemory", "dedicatedSystemMemory", "sharedSystemMemory",
                    "adapterLuid", "flags");
        }

        private String displayName() {
            String value = new String(description, StandardCharsets.UTF_16LE);
            int terminator = value.indexOf('\0');
            return (terminator >= 0 ? value.substring(0, terminator) : value).trim();
        }
    }
}
