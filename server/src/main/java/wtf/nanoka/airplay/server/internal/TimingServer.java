package wtf.nanoka.airplay.server.internal;

import lombok.extern.slf4j.Slf4j;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class TimingServer {

    private static final long NTP_EPOCH_SECONDS = 2_208_988_800L;
    private static final int PACKET_LENGTH = 32;

    private final AtomicLong remoteClockOffsetNanos = new AtomicLong(Long.MIN_VALUE);

    private DatagramSocket socket;
    private ScheduledExecutorService executor;

    public synchronized void start(InetAddress remoteAddress, int remotePort) throws Exception {
        stop();
        if (remotePort <= 0 || remotePort > 65535) {
            throw new IllegalArgumentException("Invalid remote timing port: " + remotePort);
        }

        socket = new DatagramSocket(0);
        socket.connect(remoteAddress, remotePort);
        socket.setSoTimeout(500);
        executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("airplay-timing-", 0).daemon(true).factory());
        executor.scheduleWithFixedDelay(this::exchangeTimingPacket, 0, 3, TimeUnit.SECONDS);
        log.info("AirPlay timing client listening on UDP port {}, remote {}:{}",
                socket.getLocalPort(), remoteAddress.getHostAddress(), remotePort);
    }

    public synchronized void stop() {
        if (socket != null) {
            socket.close();
            socket = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        remoteClockOffsetNanos.set(Long.MIN_VALUE);
    }

    public synchronized int getPort() {
        return socket == null ? 0 : socket.getLocalPort();
    }

    public boolean isSynchronized() {
        return remoteClockOffsetNanos.get() != Long.MIN_VALUE;
    }

    public long remoteNtpToLocalNanos(long remoteNtpTimestamp) {
        long offset = remoteClockOffsetNanos.get();
        if (offset == Long.MIN_VALUE) {
            return -1;
        }
        return ntpToUnixNanos(remoteNtpTimestamp) - offset;
    }

    private void exchangeTimingPacket() {
        DatagramSocket activeSocket;
        synchronized (this) {
            activeSocket = socket;
        }
        if (activeSocket == null || activeSocket.isClosed()) {
            return;
        }

        try {
            byte[] request = new byte[PACKET_LENGTH];
            request[0] = (byte) 0x80;
            request[1] = (byte) 0xd2;
            request[3] = 0x07;
            long transmitTime = currentNtpTime();
            ByteBuffer.wrap(request).order(ByteOrder.BIG_ENDIAN).putLong(24, transmitTime);
            activeSocket.send(new DatagramPacket(request, request.length));

            byte[] response = new byte[128];
            DatagramPacket responsePacket = new DatagramPacket(response, response.length);
            activeSocket.receive(responsePacket);
            long receiveTimeNanos = currentUnixNanos();
            if (responsePacket.getLength() < PACKET_LENGTH) {
                log.debug("Ignoring short AirPlay timing response of {} bytes", responsePacket.getLength());
                return;
            }

            ByteBuffer timing = ByteBuffer.wrap(response, 0, responsePacket.getLength()).order(ByteOrder.BIG_ENDIAN);
            long t0 = ntpToUnixNanos(timing.getLong(8));
            long t1 = ntpToUnixNanos(timing.getLong(16));
            long t2 = ntpToUnixNanos(timing.getLong(24));
            long measuredOffset = ((t1 - t0) + (t2 - receiveTimeNanos)) / 2;
            remoteClockOffsetNanos.updateAndGet(previous -> previous == Long.MIN_VALUE
                    ? measuredOffset
                    : previous + (measuredOffset - previous) / 8);
        } catch (Exception e) {
            if (!activeSocket.isClosed()) {
                log.debug("AirPlay timing exchange failed: {}", e.getMessage());
            }
        }
    }

    private static long currentNtpTime() {
        Instant now = Instant.now();
        long seconds = now.getEpochSecond() + NTP_EPOCH_SECONDS;
        long fraction = (now.getNano() * (1L << 32)) / 1_000_000_000L;
        return (seconds << 32) | (fraction & 0xffff_ffffL);
    }

    private static long currentUnixNanos() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }

    static long ntpToUnixNanos(long timestamp) {
        long seconds = Integer.toUnsignedLong((int) (timestamp >>> 32)) - NTP_EPOCH_SECONDS;
        long fraction = timestamp & 0xffff_ffffL;
        return seconds * 1_000_000_000L + ((fraction * 1_000_000_000L) >>> 32);
    }
}
