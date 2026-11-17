package at.esoren.vystormboards;

import Vystorm.vystorm_core.playermenu.PlayerMenu;
import Vystorm.vystorm_core.playermenu.PlayerMenuEntry;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Entry in Core's player menu (sneak + right-click a player): the target's places in every board ranking,
 * listed in the icon and sent to chat on click.
 */
final class BoardsMenuEntries {
    static final String PERMISSION = "vystormboards.rankings";

    private BoardsMenuEntries() {}

    static void register(BoardsPlugin plugin, Vystorm.vystorm_core.API core) {
        var platform = core.compat().platform();
        core.gui().playerMenu().register(plugin, PlayerMenuEntry.builder("rankings")
                .priority(230)
                .visibleWhen((viewer, target) -> plugin.playerMenuOn() && viewer.hasPermission(PERMISSION) && !plugin.places(target.getName()).isEmpty())
                .icon((viewer, target) -> {
                    ItemStack item = plugin.lang().name(new ItemStack(Material.GOLD_INGOT), viewer, "menu-rankings", "player", target.getName());
                    List<Component> lore = new ArrayList<>();
                    for (var place : plugin.places(target.getName()).entrySet()) {
                        if (lore.size() >= 10) break;
                        lore.add(plugin.lang().component(viewer, "menu-rankings-line", "name", plugin.label(place.getKey()), "rank", place.getValue().rank(),
                                "value", plugin.format(place.getKey(), place.getValue().value())).decoration(TextDecoration.ITALIC, false));
                    }
                    lore.add(plugin.lang().component(viewer, "menu-rankings-click").decoration(TextDecoration.ITALIC, false));
                    var meta = item.getItemMeta();
                    platform.lore(meta, lore);
                    item.setItemMeta(meta);
                    return item;
                })
                .onClick((viewer, target, click) -> {
                    plugin.sendPlaces(viewer, target.getName());
                    return PlayerMenu.Result.CLOSE;
                })
                .build());
    }
}
