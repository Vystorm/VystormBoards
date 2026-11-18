package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import Vystorm.vystorm_core.lang.Lang;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

/**
 * The left click on a board that hangs as real item frames (render-mode: entities). A hit on a fixed frame raises no
 * damage event, so the click comes from the arm swing, through Vystorm Core's real stage dispatcher. The board is one
 * tile at 0/0/0 facing north; its click area is the left half as the viewer sees it and runs the command "reward".
 */
class LeftClickEntitiesTest {
    private static final NamespacedKey KEY = NamespacedKey.minecraft("board");
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final ItemFrame frame = mock(ItemFrame.class);
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private EventStageTesting stages;
    private BoardsPlugin boards;

    @BeforeEach
    void setUp() throws Exception {
        boards = mock(BoardsPlugin.class, CALLS_REAL_METHODS);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("world: world\nx: 0\ny: 0\nz: 0\nwidth: 1\nheight: 1\nclicks: [{x: 0, y: 0, width: 64, height: 128, command: reward}]\n");
        Board board = Board.read("demo", yaml);
        Map<String, BoardRuntime> shown = new LinkedHashMap<>();
        shown.put("demo", new BoardRuntime(board, (b, v) -> "", (b, v) -> new byte[16384]));
        set("boardKey", KEY);
        set("boards", shown);
        set("lastClick", lastClick);
        set("lastDrop", new HashMap<UUID, Long>());
        set("cooldowns", new ClickCooldowns());
        set("navigation", new PageNavigation());
        set("core", TestCore.api());
        set("lang", mock(Lang.class));
        doReturn("VystormBoards").when(boards).getName();
        doReturn(true).when(boards).isEnabled();

        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(data.has(KEY, PersistentDataType.STRING)).thenReturn(true);
        when(data.get(KEY, PersistentDataType.STRING)).thenReturn("demo");
        when(frame.getPersistentDataContainer()).thenReturn(data);
        when(world.getName()).thenReturn("world");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Steve");
        when(player.getWorld()).thenReturn(world);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        lookFrom(0.75);
        inSight(frame);

        stages = EventStageTesting.detached();
        boards.handleSharedEvents(stages.stages());
    }

    private void set(String name, Object value) throws Exception {
        Field field = BoardsPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(boards, value);
    }

    /** The player stands two blocks in front of the board and looks straight at it; x 0.75 is on the click area. */
    private void lookFrom(double x) {
        when(player.getEyeLocation()).thenReturn(new Location(world, x, 0.5, -2, 0, 0));
    }

    /** What the player's line of sight meets first within reach ({@code null}: nothing). */
    private void inSight(Entity entity) {
        RayTraceResult hit = entity == null ? null : new RayTraceResult(new Vector(0.75, 0.5, 0.9375), entity);
        when(world.rayTraceEntities(any(Location.class), any(Vector.class), anyDouble(), ArgumentMatchers.<Predicate<? super Entity>>any())).thenReturn(hit);
    }

    private PlayerAnimationEvent swing() { return stages.fire(new PlayerAnimationEvent(player, PlayerAnimationType.ARM_SWING)); }

    private void commandRan(int times) { verify(player, times(times)).performCommand("reward"); }

    @Test
    void givenAnItemFrameBoard_whenAPlayerLeftClicksItsClickArea_thenTheActionRunsOnce() {
        PlayerAnimationEvent swing = swing();

        commandRan(1);
        assertFalse(swing.isCancelled(), "the arm still swings");
    }

    @Test
    void givenAnItemFrameBoard_whenAPlayerLeftClicksNextToTheClickArea_thenNothingRuns() {
        lookFrom(0.25);

        swing();

        verify(player, never()).performCommand(anyString());
    }

    @Test
    void givenALeftClick_whenASecondSwingFollowsAtOnce_thenTheClickGuardStopsItAndALaterClickRunsAgain() {
        swing();
        swing();
        commandRan(1);

        lastClick.put(player.getUniqueId(), System.currentTimeMillis() - 301);
        swing();
        commandRan(2);
    }

    @Test
    void givenARightClickOnTheFrame_whenItsArmSwingFollows_thenTheClickCountsOnce() {
        stages.fire(new PlayerInteractEntityEvent(player, frame, EquipmentSlot.HAND));
        swing();

        commandRan(1);
    }

    @Test
    void givenAHitThatDidRaiseADamageEvent_whenItsArmSwingFollows_thenTheClickCountsOnce() {
        stages.fire(new EntityDamageByEntityEvent(player, frame, EntityDamageEvent.DamageCause.ENTITY_ATTACK, mock(DamageSource.class), 1));
        swing();

        commandRan(1);
    }

    @Test
    void givenASwing_whenTheFrameIsOutOfReachOrSomethingStandsInFront_thenNothingRuns() {
        inSight(null);
        swing();
        inSight(mock(Zombie.class));
        swing();
        inSight(frame);
        when(world.rayTraceBlocks(any(Location.class), any(Vector.class), anyDouble(), any(FluidCollisionMode.class), anyBoolean()))
                .thenReturn(new RayTraceResult(new Vector(0.75, 0.5, 0), mock(Block.class), BlockFace.NORTH));
        swing();

        verify(player, never()).performCommand(anyString());
    }

    @Test
    void givenASurvivalPlayer_whenTheArmSwings_thenTheFrameIsSearchedWithinVanillaReachAndBlocksOnlyUpToTheFrame() {
        swing();

        ArgumentCaptor<Double> reach = ArgumentCaptor.forClass(Double.class), upTo = ArgumentCaptor.forClass(Double.class);
        verify(world).rayTraceEntities(any(Location.class), any(Vector.class), reach.capture(), ArgumentMatchers.<Predicate<? super Entity>>any());
        verify(world).rayTraceBlocks(any(Location.class), any(Vector.class), upTo.capture(), any(FluidCollisionMode.class), anyBoolean());
        assertEquals(3.25, reach.getValue(), 1e-9);
        assertEquals(2.9375, upTo.getValue(), 1e-9);
    }

    @Test
    void givenADroppedItem_whenTheArmSwingsForTheDrop_thenItIsNoClick() {
        boards.onDrop(new PlayerDropItemEvent(player, mock(Item.class)));

        swing();

        verify(player, never()).performCommand(anyString());
    }

    @Test
    void givenAnotherPluginCancelledTheSwing_whenItAimsAtTheClickArea_thenTheBoardStillAnswers() {
        stages.handle(EventStageTesting.plugin("AnotherPlugin"), PlayerAnimationEvent.class, EventStage.PROTECT, event -> event.setCancelled(true));

        swing();

        commandRan(1);
    }

    @Test
    void givenPacketMode_whenTheArmSwings_thenOnlyCoresWallClickCountsAndOnlyOnce() throws Exception {
        PacketBoards packets = mock(PacketBoards.class);
        when(packets.has("demo")).thenReturn(true);
        set("packets", packets);
        set("packetMode", true);

        swing();
        verify(player, never()).performCommand(anyString());
        verify(world, never()).rayTraceEntities(any(Location.class), any(Vector.class), anyDouble(), ArgumentMatchers.<Predicate<? super Entity>>any());

        // Core reports the click on its wall, the client's arm swing arrives right after
        BoardRuntime runtime = ((Map<String, BoardRuntime>) get("boards")).get("demo");
        BugFixRegressionTest.call(boards, "click", new Class<?>[]{Player.class, BoardRuntime.class}, player, runtime);
        swing();
        commandRan(1);
    }

    private Object get(String name) throws Exception {
        Field field = BoardsPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(boards);
    }
}
