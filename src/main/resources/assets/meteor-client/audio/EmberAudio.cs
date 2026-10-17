// Windows process loopback, restricted to Spotify and its children. No endpoint-wide fallback.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Threading;

public static class EmberAudio {
    [ComImport, Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IActivationOperation {
        [PreserveSig] int GetActivateResult(out int hr, [MarshalAs(UnmanagedType.IUnknown)] out object result);
    }
    [Guid("41D949AB-9862-444A-80F6-C261334DA5EB"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown), ComVisible(true)]
    public interface IActivationHandler { [PreserveSig] int ActivateCompleted(IActivationOperation operation); }
    [Guid("94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown), ComVisible(true)]
    public interface IAgileObject {}
    [DllImport("Mmdevapi.dll", ExactSpelling = true, CharSet = CharSet.Unicode)]
    static extern int ActivateAudioInterfaceAsync(string device, ref Guid iid, IntPtr parameters,
        IActivationHandler handler, out IActivationOperation operation);
    [ComVisible(true), ClassInterface(ClassInterfaceType.None)]
    public sealed class Activation : IActivationHandler, IAgileObject {
        public readonly ManualResetEvent Done = new ManualResetEvent(false);
        public IntPtr Parameters, Variant;
        public object Result;
        public int Error;
        public bool Abandoned;
        public void FreeParameters() {
            IntPtr p = Interlocked.Exchange(ref Parameters, IntPtr.Zero); if (p != IntPtr.Zero) Marshal.FreeHGlobal(p);
            p = Interlocked.Exchange(ref Variant, IntPtr.Zero); if (p != IntPtr.Zero) Marshal.FreeHGlobal(p);
        }
        public int ActivateCompleted(IActivationOperation operation) {
            try {
                object result; int error;
                Check(operation.GetActivateResult(out error, out result));
                lock (this) { Error = error; if (Abandoned) Release(result); else Result = result; }
            } catch (Exception e) { Error = e.HResult; }
            finally { FreeParameters(); Done.Set(); }
            return 0;
        }
    }
    static IAudioClient Activate(uint pid) {
        var handler = new Activation(); IActivationOperation operation = null;
        handler.Parameters = Marshal.AllocHGlobal(12);
        Marshal.WriteInt32(handler.Parameters, 0, 1); // AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK
        Marshal.WriteInt32(handler.Parameters, 4, (int) pid);
        Marshal.WriteInt32(handler.Parameters, 8, 0); // INCLUDE_TARGET_PROCESS_TREE
        int size = IntPtr.Size == 8 ? 24 : 16;
        handler.Variant = Marshal.AllocHGlobal(size);
        for (int i = 0; i < size; i++) Marshal.WriteByte(handler.Variant, i, 0);
        Marshal.WriteInt16(handler.Variant, 0, 65); // VT_BLOB
        Marshal.WriteInt32(handler.Variant, 8, 12);
        Marshal.WriteIntPtr(handler.Variant, IntPtr.Size == 8 ? 16 : 12, handler.Parameters);
        Guid iid = typeof(IAudioClient).GUID;
        int hr;
        try { hr = ActivateAudioInterfaceAsync("VAD\\Process_Loopback", ref iid, handler.Variant, handler, out operation); }
        catch { handler.FreeParameters(); throw; }
        if (hr < 0) { handler.FreeParameters(); Release(operation); Check(hr); }
        try {
            if (!handler.Done.WaitOne(5000)) {
                lock (handler) { handler.Abandoned = true; Release(handler.Result); handler.Result = null; }
                throw new TimeoutException("Spotify audio activation timed out");
            }
            Check(handler.Error);
            return (IAudioClient) handler.Result;
        } finally { Release(operation); GC.KeepAlive(handler); }
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct ProcessEntry {
        public uint size, usage, id; public IntPtr heap; public uint module, threads, parent;
        public int priority; public uint flags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string name;
    }
    [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr CreateToolhelp32Snapshot(uint flags, uint pid);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool Process32FirstW(IntPtr snapshot, ref ProcessEntry entry);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool Process32NextW(IntPtr snapshot, ref ProcessEntry entry);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    static uint FindSpotify() {
        IntPtr snapshot = CreateToolhelp32Snapshot(2, 0);
        if (snapshot == new IntPtr(-1)) return 0;
        var processes = new SortedDictionary<uint, uint>();
        try {
            var entry = new ProcessEntry(); entry.size = (uint) Marshal.SizeOf(typeof(ProcessEntry));
            if (Process32FirstW(snapshot, ref entry)) do {
                if (String.Equals(entry.name, "Spotify.exe", StringComparison.OrdinalIgnoreCase)) processes[entry.id] = entry.parent;
            } while (Process32NextW(snapshot, ref entry));
        } finally { CloseHandle(snapshot); }
        int session = Process.GetCurrentProcess().SessionId;
        return SelectRoot(processes, delegate(uint id) {
            try { using (var root = Process.GetProcessById((int) id)) return root.SessionId == session; }
            catch (ArgumentException) { return false; }
        });
    }
    static uint SelectRoot(IDictionary<uint, uint> processes, Predicate<uint> allowed) {
        uint selected = 0; int best = -1;
        foreach (var process in processes) if (!processes.ContainsKey(process.Value) && allowed(process.Key)) {
            int children = 0; foreach (uint parent in processes.Values) if (parent == process.Key) children++;
            // Prefer the full app over orphaned helper processes after a Spotify restart.
            if (children > best) { selected = process.Key; best = children; }
        }
        return selected;
    }
    [ComImport, Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioClient {
        [PreserveSig] int Initialize(int mode, uint flags, long duration, long periodicity, IntPtr format, ref Guid session);
        [PreserveSig] int GetBufferSize(out uint frames);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out uint frames);
        [PreserveSig] int IsFormatSupported(int mode, IntPtr format, out IntPtr closest);
        [PreserveSig] int GetMixFormat(out IntPtr format);
        [PreserveSig] int GetDevicePeriod(out long normal, out long minimum);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(IntPtr handle);
        [PreserveSig] int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }
    [ComImport, Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface ICapture {
        [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong position, out ulong clock);
        [PreserveSig] int ReleaseBuffer(uint frames);
        [PreserveSig] int GetNextPacketSize(out uint frames);
    }
    sealed class BassFilter {
        readonly double high, low;
        double previous, hp, lp1, lp2;
        public BassFilter(int rate) { high = Math.Exp(-2 * Math.PI * 30 / rate); low = 1 - Math.Exp(-2 * Math.PI * 180 / rate); }
        public double Next(double sample) {
            hp = high * (hp + sample - previous); previous = sample;
            lp1 += low * (hp - lp1); lp2 += low * (lp1 - lp2); return lp2;
        }
    }
    static void Check(int hr) { Marshal.ThrowExceptionForHR(hr); }
    static void Release(object value) { if (value != null && Marshal.IsComObject(value)) Marshal.ReleaseComObject(value); }
    static double Sample(byte[] data, int offset, int bits, bool floating) {
        if (floating) return bits == 32 ? BitConverter.ToSingle(data, offset) : BitConverter.ToDouble(data, offset);
        if (bits == 16) return BitConverter.ToInt16(data, offset) / 32768.0;
        if (bits == 24) return ((data[offset] | data[offset + 1] << 8 | data[offset + 2] << 16) << 8 >> 8) / 8388608.0;
        return BitConverter.ToInt32(data, offset) / 2147483648.0;
    }
    static void Capture(uint pid, double seconds, bool watchSpotify) {
        IAudioClient client = null; ICapture capture = null;
        IntPtr format = IntPtr.Zero;
        AutoResetEvent ready = null;
        try {
            Console.WriteLine("STATUS starting");
            client = Activate(pid);
            // A virtual process client has no physical endpoint mix format. Use a fixed
            // float format and let Windows convert Spotify streams, on any output device.
            format = Marshal.AllocCoTaskMem(18);
            Marshal.WriteInt16(format, 0, 3); Marshal.WriteInt16(format, 2, 2);
            Marshal.WriteInt32(format, 4, 48000); Marshal.WriteInt32(format, 8, 384000);
            Marshal.WriteInt16(format, 12, 8); Marshal.WriteInt16(format, 14, 32); Marshal.WriteInt16(format, 16, 0);
            int tag = (ushort) Marshal.ReadInt16(format, 0), channels = (ushort) Marshal.ReadInt16(format, 2);
            int rate = Marshal.ReadInt32(format, 4), align = (ushort) Marshal.ReadInt16(format, 12), bits = (ushort) Marshal.ReadInt16(format, 14);
            if (tag == 65534) tag = Marshal.ReadInt32(format, 24); // WAVEFORMATEXTENSIBLE.SubFormat
            bool floating = tag == 3;
            if (channels < 1 || channels > 32 || rate < 8000 || rate > 384000 || align < channels * (bits / 8)
                || !(floating && (bits == 32 || bits == 64) || tag == 1 && (bits == 16 || bits == 24 || bits == 32)))
                throw new NotSupportedException("Unsupported output audio format");
            Guid session = Guid.Empty;
            Check(client.Initialize(0, 0x80060000u, 0, 0, format, ref session)); // loopback + event callback + auto conversion
            ready = new AutoResetEvent(false);
            Check(client.SetEventHandle(ready.SafeWaitHandle.DangerousGetHandle()));
            Guid captureId = typeof(ICapture).GUID; object service;
            Check(client.GetService(ref captureId, out service)); capture = (ICapture) service;
            var filters = new BassFilter[channels];
            for (int i = 0; i < channels; i++) filters[i] = new BassFilter(rate);
            byte[] bytes = new byte[0]; int bytesPerSample = bits / 8;
            var timer = Stopwatch.StartNew(); double lastEmit = 0, lastDeviceCheck = 0, bassEnergy = 0, fullEnergy = 0;
            long samples = 0;
            Check(client.Start());
            while (seconds <= 0 || timer.Elapsed.TotalSeconds < seconds) {
                uint packet; Check(capture.GetNextPacketSize(out packet));
                while (packet != 0) {
                    IntPtr data; uint frames, flags; ulong position, clock;
                    Check(capture.GetBuffer(out data, out frames, out flags, out position, out clock));
                    try {
                        int length = checked((int) frames * align);
                        if (length > 4000000) throw new InvalidOperationException("Audio packet exceeds budget");
                        bool silent = (flags & 2) != 0;
                        if (!silent) {
                            if (bytes.Length < length) bytes = new byte[length];
                            Marshal.Copy(data, bytes, 0, length);
                        }
                        if ((flags & 1) != 0) for (int i = 0; i < channels; i++) filters[i] = new BassFilter(rate);
                        for (int frame = 0; frame < frames; frame++) for (int channel = 0; channel < channels; channel++) {
                            double value = silent ? 0 : Sample(bytes, frame * align + channel * bytesPerSample, bits, floating);
                            if (Double.IsNaN(value) || Double.IsInfinity(value)) value = 0;
                            value = Math.Max(-1, Math.Min(1, value));
                            double bass = filters[channel].Next(value);
                            bassEnergy += bass * bass; fullEnergy += value * value; samples++;
                        }
                    } finally { Check(capture.ReleaseBuffer(frames)); }
                    Check(capture.GetNextPacketSize(out packet));
                    // Bound work even if the producer keeps supplying packets continuously.
                    if (samples >= rate * channels / 10) break;
                }
                double now = timer.Elapsed.TotalSeconds;
                if (now - lastEmit >= 0.033) {
                    double bass = samples == 0 ? 0 : Math.Sqrt(bassEnergy / samples);
                    double rms = samples == 0 ? 0 : Math.Sqrt(fullEnergy / samples);
                    Console.WriteLine("LEVEL " + bass.ToString("F6", CultureInfo.InvariantCulture) + " " + rms.ToString("F6", CultureInfo.InvariantCulture));
                    lastEmit = now; bassEnergy = fullEnergy = 0; samples = 0;
                }
                if (watchSpotify && now - lastDeviceCheck >= 1) {
                    if (FindSpotify() != pid) return;
                    lastDeviceCheck = now;
                }
                ready.WaitOne(5);
            }
        } finally {
            if (client != null) client.Stop();
            if (format != IntPtr.Zero) Marshal.FreeCoTaskMem(format);
            Release(capture); Release(client); if (ready != null) ready.Dispose();
        }
    }
    static double Energy(double frequency) {
        var filter = new BassFilter(48000); double sum = 0;
        for (int i = 0; i < 48000; i++) { double value = filter.Next(Math.Sin(i * frequency * 2 * Math.PI / 48000) * 0.5); if (i > 4800) sum += value * value; }
        return Math.Sqrt(sum / 43200);
    }
    [MTAThread] public static int Main(string[] args) {
        try {
            if (args.Length > 0 && args[0] == "--self-test") {
                double bass = Energy(80), voice = Energy(1000), treble = Energy(8000);
                if (!(bass > 0.2 && bass > voice * 15 && bass > treble * 100)) throw new Exception("Bass band isolation failed");
                if (Sample(new byte[]{0, 0, 128}, 0, 24, false) != -1 || Sample(new byte[]{0, 128}, 0, 16, false) != -1
                    || Sample(BitConverter.GetBytes(0.25f), 0, 32, true) != 0.25) throw new Exception("Audio sample decoding failed");
                var processes = new SortedDictionary<uint, uint>{{10, 99}, {11, 10}, {12, 10}, {30, 99}};
                if (SelectRoot(processes, delegate(uint id) { return true; }) != 10
                    || SelectRoot(processes, delegate(uint id) { return id != 10; }) != 30
                    || SelectRoot(new SortedDictionary<uint, uint>(), delegate(uint id) { return true; }) != 0)
                    throw new Exception("Spotify process-tree selection failed");
                Console.WriteLine("Audio checks passed: 30-180 Hz bass isolation, PCM16/24/float decoding and Spotify process-tree selection."); return 0;
            }
            if (args.Length > 0 && args[0] == "--probe-self") { Capture((uint) Process.GetCurrentProcess().Id, 2, false); return 0; }
            if (args.Length > 0 && args[0] == "--probe") {
                uint spotify = FindSpotify();
                if (spotify == 0) { Console.WriteLine("STATUS waiting"); return 0; }
                Capture(spotify, 2, true); return 0;
            }
            // Minecraft owns stdin. EOF also ends capture after an abnormal parent exit.
            var lifetime = new Thread(delegate() {
                try { while (Console.ReadLine() != null) {} } catch (Exception) {}
                Environment.Exit(0);
            });
            lifetime.IsBackground = true; lifetime.Start();
            while (true) {
                try {
                    uint spotify = FindSpotify();
                    if (spotify == 0) { Console.WriteLine("STATUS waiting"); Thread.Sleep(1000); continue; }
                    Capture(spotify, 0, true);
                } catch (Exception e) {
                    Console.WriteLine("ERROR " + e.HResult.ToString("X8"));
                    if (e is TimeoutException) return 1;
                }
                Thread.Sleep(1500);
            }
        } catch (Exception e) { Console.Error.WriteLine(e.Message); return 1; }
    }
}
