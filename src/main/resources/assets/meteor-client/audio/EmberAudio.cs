// Windows WASAPI output loopback. Only bass/RMS levels leave this process; no audio is stored.
using System;
using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Threading;

public static class EmberAudio {
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class Enumerator {}
    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IEnum {
        [PreserveSig] int EnumAudioEndpoints(int flow, uint mask, out IntPtr devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int flow, int role, out IDevice device);
        [PreserveSig] int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IDevice device);
        [PreserveSig] int RegisterEndpointNotificationCallback(IntPtr callback);
        [PreserveSig] int UnregisterEndpointNotificationCallback(IntPtr callback);
    }
    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IDevice {
        [PreserveSig] int Activate(ref Guid iid, uint context, IntPtr parameters, [MarshalAs(UnmanagedType.IUnknown)] out object result);
        [PreserveSig] int OpenPropertyStore(uint access, out IntPtr properties);
        [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetState(out uint state);
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
    static void Capture(double seconds) {
        IEnum enumerator = null; IDevice device = null; IAudioClient client = null; ICapture capture = null;
        IntPtr format = IntPtr.Zero;
        try {
            enumerator = (IEnum) new Enumerator();
            Check(enumerator.GetDefaultAudioEndpoint(0, 1, out device)); // render / multimedia
            string deviceId; Check(device.GetId(out deviceId));
            Guid clientId = typeof(IAudioClient).GUID; object activated;
            Check(device.Activate(ref clientId, 23, IntPtr.Zero, out activated)); client = (IAudioClient) activated;
            Check(client.GetMixFormat(out format));
            int tag = (ushort) Marshal.ReadInt16(format, 0), channels = (ushort) Marshal.ReadInt16(format, 2);
            int rate = Marshal.ReadInt32(format, 4), align = (ushort) Marshal.ReadInt16(format, 12), bits = (ushort) Marshal.ReadInt16(format, 14);
            if (tag == 65534) tag = Marshal.ReadInt32(format, 24); // WAVEFORMATEXTENSIBLE.SubFormat
            bool floating = tag == 3;
            if (channels < 1 || channels > 32 || rate < 8000 || rate > 384000 || align < channels * (bits / 8)
                || !(floating && (bits == 32 || bits == 64) || tag == 1 && (bits == 16 || bits == 24 || bits == 32)))
                throw new NotSupportedException("Unsupported output audio format");
            Guid session = Guid.Empty;
            Check(client.Initialize(0, 0x20000, 1000000, 0, format, ref session)); // shared loopback
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
                    Console.WriteLine(bass.ToString("F6", CultureInfo.InvariantCulture) + " " + rms.ToString("F6", CultureInfo.InvariantCulture));
                    lastEmit = now; bassEnergy = fullEnergy = 0; samples = 0;
                }
                if (now - lastDeviceCheck >= 1) {
                    IDevice current = null;
                    try {
                        Check(enumerator.GetDefaultAudioEndpoint(0, 1, out current));
                        string id; Check(current.GetId(out id)); if (id != deviceId) return;
                    } finally { Release(current); }
                    lastDeviceCheck = now;
                }
                Thread.Sleep(5);
            }
        } finally {
            if (client != null) client.Stop();
            if (format != IntPtr.Zero) Marshal.FreeCoTaskMem(format);
            Release(capture); Release(client); Release(device); Release(enumerator);
        }
    }
    static double Energy(double frequency) {
        var filter = new BassFilter(48000); double sum = 0;
        for (int i = 0; i < 48000; i++) { double value = filter.Next(Math.Sin(i * frequency * 2 * Math.PI / 48000) * 0.5); if (i > 4800) sum += value * value; }
        return Math.Sqrt(sum / 43200);
    }
    [STAThread] public static int Main(string[] args) {
        try {
            if (args.Length > 0 && args[0] == "--self-test") {
                double bass = Energy(80), voice = Energy(1000), treble = Energy(8000);
                if (!(bass > 0.2 && bass > voice * 15 && bass > treble * 100)) throw new Exception("Bass band isolation failed");
                if (Sample(new byte[]{0, 0, 128}, 0, 24, false) != -1 || Sample(new byte[]{0, 128}, 0, 16, false) != -1
                    || Sample(BitConverter.GetBytes(0.25f), 0, 32, true) != 0.25) throw new Exception("Audio sample decoding failed");
                Console.WriteLine("Audio checks passed: 30-180 Hz bass isolation, PCM16/24 and float decoding."); return 0;
            }
            if (args.Length > 0 && args[0] == "--probe") { Capture(2); return 0; }
            // Minecraft owns stdin. EOF also ends capture after an abnormal parent exit.
            var lifetime = new Thread(delegate() {
                try { while (Console.ReadLine() != null) {} } catch (Exception) {}
                Environment.Exit(0);
            });
            lifetime.IsBackground = true; lifetime.Start();
            while (true) { try { Capture(0); } catch (Exception e) { Console.Error.WriteLine(e.Message); } Thread.Sleep(1500); }
        } catch (Exception e) { Console.Error.WriteLine(e.Message); return 1; }
    }
}
