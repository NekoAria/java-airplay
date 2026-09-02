package wtf.nanoka.airplay.server.internal.handler.session;

import org.junit.jupiter.api.Test;
import wtf.nanoka.airplay.lib.AirPlayIdentity;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerTest {

    @Test
    void tracksActivePairingConnectionsPerPeer() throws Exception {
        var sessions = new SessionManager(AirPlayIdentity.random(), 4);
        var peer = InetAddress.getByName("192.0.2.10");

        sessions.markPeerPaired(peer);
        sessions.markPeerPaired(peer);
        sessions.unmarkPeerPaired(peer);

        assertTrue(sessions.isPeerPaired(peer));
        sessions.unmarkPeerPaired(peer);
        assertFalse(sessions.isPeerPaired(peer));
    }

    @Test
    void releasesNamedHttpSessionAfterItsLastConnectionCloses() {
        var sessions = new SessionManager(AirPlayIdentity.random(), 4);
        var session = sessions.getSession("http-session");

        assertTrue(sessions.retainHttpSession(session));
        assertTrue(sessions.retainHttpSession(session));
        sessions.releaseHttpSession(session.getId());
        assertSame(session, sessions.findSession(session.getId()));

        sessions.releaseHttpSession(session.getId());
        assertNull(sessions.findSession(session.getId()));
    }

    @Test
    void limitsAuthorizedHttpSessionsPerPeer() throws Exception {
        var sessions = new SessionManager(AirPlayIdentity.random(), 4);
        var peer = InetAddress.getByName("192.0.2.20");

        for (int index = 0; index < 32; index++) {
            var session = sessions.getSession("session-" + index);
            assertTrue(sessions.authorizeHttpSession(session, peer));
        }

        assertFalse(sessions.authorizeHttpSession(sessions.getSession("session-over-limit"), peer));
    }
}
