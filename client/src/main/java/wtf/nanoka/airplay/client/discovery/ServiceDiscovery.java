package wtf.nanoka.airplay.client.discovery;

import lombok.extern.slf4j.Slf4j;

import javax.jmdns.JmDNS;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class ServiceDiscovery {

    private static final String AIRPLAY_SERVICE_TYPE = "_airplay._tcp.local.";

    public Set<Info> discover() {
        Set<Info> result = new HashSet<>();
        log.info("Searching AirPlay services on active IPv4 interfaces");
        try {
            var addresses = NetworkInterface.networkInterfaces()
                    .filter(networkInterface -> {
                        try {
                            return networkInterface.isUp() && !networkInterface.isLoopback()
                                    && !networkInterface.isPointToPoint();
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .flatMap(NetworkInterface::inetAddresses)
                    .filter(Inet4Address.class::isInstance)
                    .toList();
            for (InetAddress address : addresses) {
                try (JmDNS jmdns = JmDNS.create(address)) {
                    Arrays.stream(jmdns.list(AIRPLAY_SERVICE_TYPE))
                            .filter(serviceInfo -> serviceInfo.getInet4Addresses().length > 0)
                            .map(serviceInfo -> new Info(serviceInfo.getName(),
                                    serviceInfo.getInet4Addresses()[0].getHostAddress(), serviceInfo.getPort()))
                            .forEach(result::add);
                } catch (Exception e) {
                    log.debug("Unable to search AirPlay services on {}: {}", address, e.getMessage());
                }
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public record Info(String name, String address, int port) {
    }
}
