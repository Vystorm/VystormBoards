package at.esoren.vystormboards;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Session-only page selections; automatic rotation continues independently for other viewers. */
final class PageNavigation {
    private final Map<UUID, Map<String, Integer>> selections = new HashMap<>();

    /** page-navigation.enabled: while off nobody has a personal page and every viewer follows the rotation. */
    private boolean off;

    boolean enabled() { return !off; }
    void enabled(boolean on) { off = !on; if (off) selections.clear(); }

    int page(Board board, UUID viewer, long now) {
        if (off) return board.page(now);
        Map<String, Integer> selected = selections.get(viewer);
        return selected == null ? board.page(now) : selected.getOrDefault(board.name, board.page(now));
    }

    boolean navigate(Board board, UUID viewer, String action, long now) {
        if (off || viewer == null || board.pages.isEmpty()) return false;
        if (action.equals("auto")) {
            Map<String, Integer> selected = selections.get(viewer);
            if (selected != null) {
                selected.remove(board.name);
                if (selected.isEmpty()) selections.remove(viewer);
            }
            return true;
        }
        int current = page(board, viewer, now), target;
        switch (action) {
            case "next" -> target = (current + 1) % board.pages.size();
            case "previous" -> target = Math.floorMod(current - 1, board.pages.size());
            default -> {
                try { target = Integer.parseInt(action) - 1; }
                catch (NumberFormatException invalid) { return false; }
                if (target < 0 || target >= board.pages.size()) return false;
            }
        }
        selections.computeIfAbsent(viewer, id -> new HashMap<>()).put(board.name, target);
        return true;
    }

    void forget(UUID viewer) { selections.remove(viewer); }
    void clear() { selections.clear(); }
}
