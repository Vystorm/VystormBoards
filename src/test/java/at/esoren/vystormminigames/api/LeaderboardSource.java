package at.esoren.vystormminigames.api;

import java.util.List;
import java.util.UUID;

/**
 * TEST STAND-IN with the binary name and shape of Vystorm Minigames' service interface (see
 * docs/leaderboard-source.md in the Minigames repository). It exists only in the test sources: Boards' main code must
 * find it by name and call it by reflection, and would not compile if it referred to this type.
 */
public interface LeaderboardSource {
    record Row(UUID player, String name, long value) {}

    List<Row> top(String key, int n);
}
