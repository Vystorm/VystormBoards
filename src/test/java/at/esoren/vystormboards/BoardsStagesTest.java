package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The frame handlers of Boards, called by Vystorm Core's real stage dispatcher together with the handlers of a second
 * plugin: the frame of a board is protected in PROTECT, so nothing a player does to it reaches an ADJUST, ACT or
 * OBSERVE handler, and the click on the board (ACT, for the cancelled event) only runs after every protection.
 */
class BoardsStagesTest {
    private static final NamespacedKey KEY = NamespacedKey.minecraft("board");
    private final List<String> calls = new ArrayList<>();
    private final Plugin other = EventStageTesting.plugin("AnotherPlugin");
    private final Player player = mock(Player.class);
    private final PersistentDataContainer boardData = mock(PersistentDataContainer.class);
    private final ItemFrame boardFrame = frame(boardData), plainFrame = frame(mock(PersistentDataContainer.class));
    private EventStageTesting stages;
    private BoardsPlugin boards;

    @BeforeEach
    void setUp() throws Exception {
        boards = mock(BoardsPlugin.class, CALLS_REAL_METHODS);
        for (var entry : Map.<String, Object>of("boardKey", KEY, "boards", new LinkedHashMap<String, Object>(), "lastClick", new HashMap<>()).entrySet()) {
            Field field = BoardsPlugin.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(boards, entry.getValue());
        }
        doReturn("VystormBoards").when(boards).getName();
        doReturn(true).when(boards).isEnabled();
        when(boardData.has(KEY, PersistentDataType.STRING)).thenReturn(true);
        when(boardData.get(KEY, PersistentDataType.STRING)).thenReturn("spawn");
        stages = EventStageTesting.detached();
    }

    private static ItemFrame frame(PersistentDataContainer data) {
        ItemFrame frame = mock(ItemFrame.class);
        when(frame.getPersistentDataContainer()).thenReturn(data);
        return frame;
    }

    /** The click handler looks the board up by the name stored on the frame: that lookup shows that it ran. */
    private boolean boardLookedUp() {
        return mockingDetails(boardData).getInvocations().stream().anyMatch(call -> call.getMethod().getName().equals("get"));
    }

    /** A second plugin that registers first and sorts first by name: only the stage puts the frame protection ahead. */
    private <E extends Event> void anotherPluginHandles(Class<E> type) {
        for (EventStage stage : List.of(EventStage.ADJUST, EventStage.ACT, EventStage.OBSERVE))
            stages.handle(other, type, stage, event -> calls.add(stage.name()));
        boards.handleSharedEvents(stages.stages());
    }

    @Test
    void givenABoard_whenAPlayerRightClicksItsFrame_thenNoLaterStageSeesTheClickAndTheBoardAnswers() {
        anotherPluginHandles(PlayerInteractEntityEvent.class);
        // a protection of another plugin that would complain about the click: it never gets the chance
        stages.handle(EventStageTesting.plugin("ZoneProtection"), PlayerInteractEntityEvent.class, EventStage.PROTECT, event -> calls.add("another protection"));

        PlayerInteractEntityEvent click = stages.fire(new PlayerInteractEntityEvent(player, boardFrame, EquipmentSlot.HAND));

        assertTrue(click.isCancelled());
        assertEquals(List.of(), calls);
        assertTrue(boardLookedUp());
    }

    @Test
    void givenABoard_whenItsFrameIsClicked_thenTheBoardOnlyAnswersAfterEveryProtection() {
        stages.handle(other, PlayerInteractEntityEvent.class, EventStage.ADJUST, true, event -> calls.add("adjust, cancelled: " + event.isCancelled() + ", board answered: " + boardLookedUp()));
        boards.handleSharedEvents(stages.stages());

        stages.fire(new PlayerInteractEntityEvent(player, boardFrame, EquipmentSlot.HAND));

        assertEquals(List.of("adjust, cancelled: true, board answered: false"), calls);
        assertTrue(boardLookedUp());
    }

    @Test
    void givenABoard_whenAPlayerHitsItsFrame_thenNoLaterStageSeesTheHitAndTheBoardAnswers() {
        anotherPluginHandles(EntityDamageEvent.class);

        EntityDamageByEntityEvent hit = stages.fire(new EntityDamageByEntityEvent(player, boardFrame, EntityDamageEvent.DamageCause.ENTITY_ATTACK, mock(DamageSource.class), 1));

        assertTrue(hit.isCancelled());
        assertEquals(List.of(), calls);
        assertTrue(boardLookedUp());
    }

    @Test
    void givenABoard_whenItsFrameWouldBreak_thenNoLaterStageSeesTheBreak() {
        anotherPluginHandles(HangingBreakEvent.class);

        assertTrue(stages.fire(new HangingBreakByEntityEvent(boardFrame, player)).isCancelled());
        assertTrue(stages.fire(new HangingBreakEvent(boardFrame, HangingBreakEvent.RemoveCause.EXPLOSION)).isCancelled());

        assertEquals(List.of(), calls);
    }

    @Test
    void givenAnOrdinaryFrame_whenItIsUsedHitOrBroken_thenBoardsStaysOutOfIt() {
        anotherPluginHandles(PlayerInteractEntityEvent.class);
        for (EventStage stage : List.of(EventStage.ADJUST, EventStage.ACT, EventStage.OBSERVE)) {
            stages.handle(other, EntityDamageEvent.class, stage, event -> calls.add(stage.name()));
            stages.handle(other, HangingBreakEvent.class, stage, event -> calls.add(stage.name()));
        }

        assertFalse(stages.fire(new PlayerInteractEntityEvent(player, plainFrame, EquipmentSlot.HAND)).isCancelled());
        assertFalse(stages.fire(new EntityDamageByEntityEvent(player, plainFrame, EntityDamageEvent.DamageCause.ENTITY_ATTACK, mock(DamageSource.class), 1)).isCancelled());
        assertFalse(stages.fire(new HangingBreakByEntityEvent(plainFrame, player)).isCancelled());

        assertEquals(List.of("ADJUST", "ACT", "OBSERVE", "ADJUST", "ACT", "OBSERVE", "ADJUST", "ACT", "OBSERVE"), calls);
        assertFalse(boardLookedUp());
    }
}
