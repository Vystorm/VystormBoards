package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.display.ClientDisplays;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class RenderCompatibilityTest {
    private BoardsPlugin plugin(String mode) throws Exception {
        BoardsPlugin plugin = mock(BoardsPlugin.class);
        BugFixRegressionTest.set(plugin, "core", TestCore.api());
        YamlConfiguration config = new YamlConfiguration();
        config.set("render-mode", mode);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        return plugin;
    }

    private void resolve(BoardsPlugin plugin) throws Exception {
        var method = BoardsPlugin.class.getDeclaredMethod("resolveRenderMode");
        method.setAccessible(true);
        method.invoke(plugin);
    }

    private Object field(BoardsPlugin plugin, String name) throws Exception {
        var field = BoardsPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(plugin);
    }

    @Test void supportedServerKeepsPacketMode() throws Exception {
        try (var core = mockStatic(CoreApi.class); var displays = mockStatic(ClientDisplays.class)) {
            core.when(() -> CoreApi.has(CoreApi.Capability.CLIENT_DISPLAYS)).thenReturn(true);
            displays.when(ClientDisplays::supported).thenReturn(true);
            BoardsPlugin plugin = plugin("auto");
            resolve(plugin);
            assertEquals(true, field(plugin, "packetMode"));
            assertNotNull(field(plugin, "packets"));
            assertNull(field(plugin, "packetUnavailable"));
        }
    }

    @Test void unsupportedServerFallsBackInAutoAndExplicitPacketMode() throws Exception {
        try (var core = mockStatic(CoreApi.class); var displays = mockStatic(ClientDisplays.class)) {
            core.when(() -> CoreApi.has(CoreApi.Capability.CLIENT_DISPLAYS)).thenReturn(true);
            displays.when(ClientDisplays::supported).thenReturn(false);
            displays.when(ClientDisplays::unsupportedReason).thenReturn(Optional.of("packet API unavailable"));
            for (String mode : new String[]{"auto", "packets"}) {
                BoardsPlugin plugin = plugin(mode);
                resolve(plugin);
                assertEquals(false, field(plugin, "packetMode"));
                assertNull(field(plugin, "packets"));
                assertEquals("packet API unavailable", field(plugin, "packetUnavailable"));
                if (mode.equals("packets")) verify(plugin.getLogger()).warning(
                        "render-mode: packets is not available (packet API unavailable); boards use item frames.");
            }
        }
    }

    @Test void explicitEntitiesNeverProbePacketSupport() throws Exception {
        try (var core = mockStatic(CoreApi.class); var displays = mockStatic(ClientDisplays.class)) {
            BoardsPlugin plugin = plugin("entities");
            resolve(plugin);
            assertEquals(false, field(plugin, "packetMode"));
            assertNull(field(plugin, "packetUnavailable"));
            core.verifyNoInteractions();
            displays.verifyNoInteractions();
        }
    }
}
