package at.esoren.vystormboards;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotPhotosTest {
    private static YamlConfiguration album() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("photos.3.name", "Alex");
        y.set("photos.7.name", "Bo");
        y.set("photos.9.name", "Alex");
        y.set("photos.10.name", "Alex");
        y.set("photos.10.part", "1/4");
        return y;
    }

    private static YamlConfiguration gallery() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("exhibits.3.published", true);
        y.set("exhibits.7.published", false);
        y.set("exhibits.9.published", true);
        return y;
    }

    @Test
    void givenSources_whenSelected_thenNewestFirstWithoutParts() {
        assertEquals(List.of(9, 7, 3), SnapshotPhotos.select(album(), gallery(), "snapshot:newest", 10));
        assertEquals(List.of(9, 3), SnapshotPhotos.select(album(), gallery(), "snapshot:player:alex", 10));
        assertEquals(List.of(9, 3), SnapshotPhotos.select(album(), gallery(), "snapshot:gallery", 10), "unpublished exhibits are left out");
        assertEquals(List.of(9), SnapshotPhotos.select(album(), gallery(), "snapshot:newest", 1));
        assertEquals(List.of(), SnapshotPhotos.select(album(), gallery(), "snapshot:other", 10));
        assertTrue(SnapshotPhotos.source("Snapshot:gallery"));
        assertFalse(SnapshotPhotos.source("logo.png"));
    }
}
