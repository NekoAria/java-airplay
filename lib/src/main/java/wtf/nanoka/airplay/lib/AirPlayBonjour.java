package wtf.nanoka.airplay.lib;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Registers airplay/airtunes service mdns
 */
@Slf4j
@RequiredArgsConstructor
public class AirPlayBonjour {

    private static final String AIRPLAY_SERVICE_TYPE = "._airplay._tcp.local";
    private static final String AIRTUNES_SERVICE_TYPE = "._raop._tcp.local";

    private final String serverName;
    private final AirPlayIdentity identity;
    private final boolean hevcEnabled;

    private final List<JmDNS> jmDNSList = new ArrayList<>();

    public synchronized void start(int airTunesPort) throws Exception {
        if (!jmDNSList.isEmpty()) {
            return;
        }
        List<JmDNS> startedServices = new ArrayList<>();
        String mac = identity.getDeviceId();
        try {
            NetworkInterface.networkInterfaces()
                    .filter(networkInterfaceFilter())
                    .flatMap(NetworkInterface::inetAddresses)
                    .filter(inetAddressFilter())
                    .forEach(inetAddress -> {
                        JmDNS jmDNS = null;
                        try {
                            jmDNS = JmDNS.create(inetAddress);
                            jmDNS.registerService(ServiceInfo.create(serverName + AIRPLAY_SERVICE_TYPE,
                                    serverName, airTunesPort, 0, 0, airPlayMDNSProps(mac)));
                            log.info("{} service is registered on address {}, port {}", serverName + AIRPLAY_SERVICE_TYPE,
                                    inetAddress.getHostAddress(), airTunesPort);

                            String airTunesServerName = mac.replaceAll(":", "") + "@" + serverName;
                            jmDNS.registerService(ServiceInfo.create(airTunesServerName + AIRTUNES_SERVICE_TYPE,
                                    airTunesServerName, airTunesPort, 0, 0, airTunesMDNSProps()));
                            log.info("{} service is registered on address {}, port {}", airTunesServerName + AIRTUNES_SERVICE_TYPE,
                                    inetAddress.getHostAddress(), airTunesPort);
                            startedServices.add(jmDNS);
                        } catch (Exception e) {
                            close(jmDNS);
                            log.warn("Unable to register AirPlay services on {}: {}",
                                    inetAddress.getHostAddress(), e.getMessage());
                        }
                    });
            if (startedServices.isEmpty()) {
                throw new IOException("No network interface accepted the AirPlay Bonjour services");
            }
            jmDNSList.addAll(startedServices);
        } catch (Exception e) {
            closeAll(startedServices);
            throw e;
        }
    }

    public synchronized void stop() {
        closeAll(jmDNSList);
        jmDNSList.clear();
    }

    static void closeAll(List<? extends AutoCloseable> services) {
        List<Thread> closingThreads = new ArrayList<>(services.size());
        Thread.Builder.OfVirtual threadBuilder = Thread.ofVirtual()
                .name("airplay-bonjour-close-", 0);
        for (AutoCloseable service : services) {
            closingThreads.add(threadBuilder.start(() -> close(service)));
        }

        boolean wasInterrupted = false;
        for (Thread thread : closingThreads) {
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException ignored) {
                    wasInterrupted = true;
                }
            }
        }
        if (wasInterrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void close(AutoCloseable service) {
        if (service == null) {
            return;
        }
        try {
            // JmDNS.close() unregisters all services before releasing the socket.
            service.close();
        } catch (Exception error) {
            log.debug("Unable to close mDNS service: {}", error.getMessage());
        }
    }

    private Map<String, String> airPlayMDNSProps(String deviceId) {
        HashMap<String, String> airPlayMDNSProps = new HashMap<>();
        airPlayMDNSProps.put("deviceid", deviceId);
        airPlayMDNSProps.put("features", AirPlayFeatures.txtValue(hevcEnabled));
        airPlayMDNSProps.put("srcvers", "220.68");
        airPlayMDNSProps.put("flags", "0x44");
        airPlayMDNSProps.put("vv", "2");
        airPlayMDNSProps.put("model", "AppleTV3,2C");
        airPlayMDNSProps.put("rhd", "5.6.0.0");
        airPlayMDNSProps.put("pw", "false");
        airPlayMDNSProps.put("pk", identity.getPublicKeyHex());
        //airPlayMDNSProps.put("pi", "2e388006-13ba-4041-9a67-25dd4a43d536");
        airPlayMDNSProps.put("rmodel", "PC1.0");
        airPlayMDNSProps.put("rrv", "1.01");
        airPlayMDNSProps.put("rsv", "1.00");
        airPlayMDNSProps.put("pcversion", "1715");
        return airPlayMDNSProps;
    }

    private Map<String, String> airTunesMDNSProps() {
        HashMap<String, String> airTunesMDNSProps = new HashMap<>();
        airTunesMDNSProps.put("ch", "2");
        airTunesMDNSProps.put("cn", "1,3");
        airTunesMDNSProps.put("da", "true");
        airTunesMDNSProps.put("et", "0,3,5");
        airTunesMDNSProps.put("ek", "1");
        //airTunesMDNSProps.put("vv", "2");
        airTunesMDNSProps.put("ft", AirPlayFeatures.txtValue(hevcEnabled));
        airTunesMDNSProps.put("am", "AppleTV3,2C");
        airTunesMDNSProps.put("md", "0,1,2");
        //airTunesMDNSProps.put("rhd", "5.6.0.0");
        //airTunesMDNSProps.put("pw", "false");
        airTunesMDNSProps.put("sr", "44100");
        airTunesMDNSProps.put("ss", "16");
        airTunesMDNSProps.put("sv", "false");
        airTunesMDNSProps.put("sm", "false");
        airTunesMDNSProps.put("tp", "UDP");
        airTunesMDNSProps.put("txtvers", "1");
        airTunesMDNSProps.put("sf", "0x44");
        airTunesMDNSProps.put("vs", "220.68");
        airTunesMDNSProps.put("vn", "65537");
        airTunesMDNSProps.put("pk", identity.getPublicKeyHex());
        return airTunesMDNSProps;
    }

    private Predicate<NetworkInterface> networkInterfaceFilter() {
        return networkInterface -> {
            try {
                return !networkInterface.isLoopback() && !networkInterface.isPointToPoint() && networkInterface.isUp();
            } catch (SocketException e) {
                return false;
            }
        };
    }

    private Predicate<InetAddress> inetAddressFilter() {
        return inetAddress -> inetAddress instanceof Inet4Address /*|| inetAddress instanceof Inet6Address*/;
    }

}
