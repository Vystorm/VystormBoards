package at.esoren.vystormboards;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import Vystorm.vystorm_core.commands.Arg;
import Vystorm.vystorm_core.commands.CommandContext;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.schedule.TaskGroup;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.util.Vector;

/**
 * Vystorm Boards: dynamic billboards made of item frames and maps. Boards show images, MiniMessage texts with
 * placeholders (built-in and PlaceholderAPI), player faces and coloured shapes, can differ per viewer and run
 * commands when clicked. Defined in boards.yml; {@code /boards create} hangs a new one on the wall you look at.
 */
public final class BoardsPlugin extends JavaPlugin implements Listener {
    /** The Core hub; set first thing in onEnable. */
    private API core;
    private Lang lang;
    private MapColors colors;
    private Assets assets;
    private Composer composer;
    private BoardEditor editor;
    private File boardsFile;
    private NamespacedKey boardKey;
    private final Map<String, BoardRuntime> boards = new LinkedHashMap<>();
    private final Map<Integer, BoardRuntime.Tile> tilesByMap = new HashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();
    /** When a player last dropped an item: the client swings its arm for that too, which is no click on a board. */
    private final Map<UUID, Long> lastDrop = new HashMap<>();
    private final ClickCooldowns cooldowns = new ClickCooldowns();
    private final PageNavigation navigation = new PageNavigation();
    private Method papi;
    private int fps = 4;
    private TaskGroup tasks;
    private final Leaderboards leaderboards = new Leaderboards();
    private final Polls polls = new Polls();
    private final Guestbooks guestbooks = new Guestbooks();
    private final Map<UUID, Long> guestbookCooldowns = new HashMap<>();
    private File leaderboardsFile, pollsFile, guestbooksFile;
    /** Data files that failed to load: never overwritten, so a typo cannot wipe rankings or votes. */
    private final Set<File> unreadable = new HashSet<>();
    private boolean savePending, rankingsOn, pollsOn, allowChange, seedOffline, playerMenuOn;
    /** Feature switches read in load(): world-clock.enabled, page-editor.enabled and snapshot-photos.enabled set to false. */
    private boolean clockOff, editorOff, photosOff;
    private int maxPlayers = 1000;
    /** Ranking sources in use (first top element per source), stat/placeholder sources that get sampled. */
    private final Map<String, Board.Top> rankingTops = new LinkedHashMap<>();
    private final Set<String> sampled = new HashSet<>();
    /** minigames:COURSE sources in use: read from Vystorm Minigames on the same timer as the sampled ones. */
    private Set<String> courseSources = new HashSet<>();
    private MinigamesSource minigames = new MinigamesSource(Bukkit::getServicesManager, this::getLogger);
    private final Set<String> badSources = new HashSet<>();
    /** Poll ids used by click areas on the boards (shown in /boards poll before the first vote). */
    private final Set<String> boardPolls = new java.util.TreeSet<>();
    private record Cached(List<Leaderboards.Ranked> ranking, long at) {}
    private final Map<String, Cached> objectiveCache = new HashMap<>();
    private BukkitTask seeding;
    /** render-mode as configured (auto, packets, entities) and whether boards currently run as Core packet walls. */
    private String renderMode = "auto";
    private boolean packetMode;
    private String packetUnavailable;
    private PacketBoards packets;
    /** Every feature switch as read in load(); all on until the first load (and in tests that build the plugin by hand). */
    private Feature.Switches switches;

    /** Minimum Vystorm Core: placeholders through api.placeholders(). */
    static final String MIN_CORE = "0.65.0";

    @Override
    public void onEnable() {
        if (!CoreApi.atLeast(MIN_CORE)) {
            getLogger().severe("Vystorm Core " + MIN_CORE + " or newer is required; please update Core first.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        core = CoreApi.api().orElseThrow();

        core.admin().updates().spigot(this, 140052);
        saveDefaultConfig();
        lang = core.lang().register(this);
        registerTutorial();
        boardKey = new NamespacedKey(this, "board");
        colors = new MapColors();
        File images = new File(getDataFolder(), "images");
        if (!images.isDirectory() && images.mkdirs()) saveResource("images/vystorm-logo.png", false);
        assets = new Assets(this, core.compat().platform(), images.toPath(), colors, () -> boards.values().forEach(BoardRuntime::invalidate));
        composer = new Composer(assets, assets::face);
        boardsFile = new File(getDataFolder(), "boards.yml");
        if (!boardsFile.exists()) saveResource("boards.yml", false);
        tasks = core.tasks().of(this, "boards");
        leaderboardsFile = new File(getDataFolder(), "leaderboards.yml");
        pollsFile = new File(getDataFolder(), "polls.yml");
        YamlConfiguration saved = readData(leaderboardsFile);
        if (saved != null) leaderboards.load(saved);
        saved = readData(pollsFile);
        if (saved != null) polls.load(saved);
        guestbooksFile = new File(getDataFolder(), "guestbooks.yml");
        saved = readData(guestbooksFile);
        if (saved != null) guestbooks.load(saved);
        composer.rankings(this::ranking);
        load();
        editor = new BoardEditor(this, core, boardsFile.toPath());
        editor.registerPanel();
        getServer().getPluginManager().registerEvents(editor, this);
        getServer().getPluginManager().registerEvents(this, this);
        handleSharedEvents(core.priority().stages());
        core.settings().config().register(this, "config.yml", this::load);
        core.settings().config().registerAll(this, this::load);
        declareSwitches(core.settings().config(), this, lang);
        try { registerPlaceholders(core.placeholders()); }
        catch (RuntimeException failure) { getLogger().log(Level.WARNING, "Placeholders not registered", failure); }
        registerCommands();
        BoardsMenuEntries.register(this, core);
    }

    /** The tutorial in /vmenu unless tutorial.enabled is false; read once, because Core lists tutorials when a plugin starts. */
    void registerTutorial() {
        if (!getConfig().getBoolean(Feature.TUTORIAL.path, true)) return;
        try { core.registerTutorial(this); } catch (RuntimeException noTutorial) { getLogger().fine("Tutorial not registered: " + noTutorial); }
    }

    /** Every switch in /vadmin features with its one-line description in the admin's language. */
    static void declareSwitches(Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface config, org.bukkit.plugin.Plugin plugin, Lang lang) {
        for (Feature feature : Feature.values()) {
            config.feature(plugin, feature.path, lang.ref(feature.label()), lang.ref(feature.description()));
            if (feature.needsRestart()) config.activation(plugin, feature.path, Vystorm.vystorm_core._uiMethods.settings.SettingPolicy.Activation.RESTART);
        }
    }

    /** Registers /boards once; the subcommands of switched-off features stay out of help and tab completion (asked live). */
    private void registerCommands() {
        core.command().register(this, lang, commandSpec(this));
    }

    /** The namespace {@code boards} with the values of polls, guestbooks and rankings (server thread; empty when switched off). */
    void registerPlaceholders(Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface registry) {
        BoardsPlaceholders.register(registry, this, new BoardsPlaceholders.Source() {
            @Override public boolean on(Feature feature) { return BoardsPlugin.this.on(feature); }
            @Override public Polls polls() { return polls; }
            @Override public Guestbooks guestbooks() { return guestbooks; }
            @Override public java.util.Collection<String> rankingSources() { return rankingTops.keySet(); }
            @Override public List<Leaderboards.Ranked> ranking(String source) { Board.Top top = rankingTops.get(source); return BoardsPlugin.this.ranking(source, top != null && top.lowest()); }
            @Override public String format(String source, double value) { return BoardsPlugin.this.format(source, value); }
            @Override public int boards() { return boards.size(); }
        }, key -> lang == null ? key : lang.ref(key));
    }

    // ------------------------------------------------------------------ feature switches

    private Feature.Switches switches() { return switches == null ? Feature.Switches.without() : switches; }

    private boolean on(Feature feature) { return switches().on(feature); }

    /** True (and the shared off message sent) when {@code feature} or one it depends on is switched off. */
    private boolean off(CommandSender sender, Feature... features) {
        for (Feature feature : features) {
            if (on(feature)) continue;
            lang.send(sender, "feature-off");
            return true;
        }
        return false;
    }

    @Override
    public void onDisable() {
        if (editor != null) editor.close();
        if (tasks != null) tasks.close();
        saveData();
        if (packets != null) packets.clear();
        for (var entry : tilesByMap.entrySet()) {
            MapView view = Bukkit.getMap(entry.getKey());
            if (view != null) view.removeRenderer(entry.getValue());
        }
        tilesByMap.clear();
        boards.clear();
    }

    /** The YAML error of config.yml found by the last load ("line L, column C: ..."), null when it parsed. */
    private String configProblem;
    private boolean configLoaded;

    /**
     * Reads config.yml for start and reload. A file with a YAML error is left byte-identical and the error logged once;
     * false is returned so that a reload keeps the last good settings and boards (Bukkit would hand back the jar defaults).
     */
    boolean readConfig() {
        configProblem = YamlCheck.problem(new File(getDataFolder(), "config.yml"));
        if (configProblem != null) {
            getLogger().severe("config.yml has a YAML error at " + configProblem + ". The file was left unchanged; "
                    + (configLoaded ? "the last working settings stay in use." : "the default settings are used.") + " Fix it and run /boards reload.");
            return false;
        }
        reloadConfig();
        return true;
    }

    private void load() {
        if (!readConfig() && configLoaded) return;
        configLoaded = true;
        navigation.clear();
        if (lang != null) lang.reload();
        assets.clear();
        fps = Math.clamp(getConfig().getInt("animation-fps", 4), 1, 10);
        composer.maxFps(fps);
        Feature.Switches before = switches;
        switches = Feature.Switches.read(getConfig());
        composer.switches(switches);
        boolean master = on(Feature.BOARDS);
        rankingsOn = master && on(Feature.RANKINGS);
        seedOffline = on(Feature.OFFLINE_STATS);
        playerMenuOn = on(Feature.RANKINGS_MENU);
        maxPlayers = Math.clamp(getConfig().getInt("leaderboards.max-players", 1000), 10, 100_000);
        pollsOn = master && on(Feature.POLLS);
        polls.deadlines(on(Feature.POLL_DEADLINES));
        allowChange = on(Feature.VOTE_CHANGE);
        clockOff = !on(Feature.CLOCK);
        navigation.enabled(on(Feature.NAVIGATION));
        editorOff = !master || !on(Feature.EDITOR);
        photosOff = !on(Feature.PHOTOS);
        if (editorOff && editor != null) editor.endSessions();
        saveData();
        tasks.cancel();
        savePending = false;
        seeding = null;
        objectiveCache.clear();
        badSources.clear();
        papi = null;
        if (on(Feature.PLACEHOLDERAPI) && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, Bukkit.getPluginManager().getPlugin("PlaceholderAPI").getClass().getClassLoader())
                        .getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                getLogger().warning("PlaceholderAPI found but not usable: " + failure);
            }
        }
        for (var entry : tilesByMap.entrySet()) {
            MapView view = Bukkit.getMap(entry.getKey());
            if (view != null) view.removeRenderer(entry.getValue());
        }
        tilesByMap.clear();
        if (packets != null) packets.clear();
        boards.clear();
        rankingTops.clear();
        sampled.clear();
        // tests build the plugin without field initialisers: create the Minigames parts here when missing
        if (courseSources == null) courseSources = new HashSet<>();
        if (minigames == null) minigames = new MinigamesSource(Bukkit::getServicesManager, this::getLogger);
        courseSources.clear();
        boardPolls.clear();
        resolveRenderMode();
        double configuredDistance = getConfig().getDouble("packet-view-distance", 48);
        double viewDistance = Double.isFinite(configuredDistance) ? Math.clamp(configuredDistance, 8, 256) : 48;
        // boards.enabled off: no board is read, drawn or clickable; boards.yml and the item frames on the walls stay
        YamlConfiguration yaml = master ? readBoards() : null;
        ConfigurationSection section = yaml == null ? null : yaml.getConfigurationSection("boards");
        boolean personal = on(Feature.PER_PLAYER);
        if (section != null) {
            for (String name : section.getKeys(false)) {
                try {
                    Board board = Board.read(name, section.getConfigurationSection(name));
                    BoardRuntime runtime = new BoardRuntime(board, this::sign, this::compose, checkTicks(board), personal);
                    if (packetMode && packets.open(runtime, viewDistance, this::click)) {
                        // packet wall confirmed: the real frames go (they are re-hung from boards.yml in entity mode)
                        removeFrames(board, null);
                    } else {
                        if (packetMode) getLogger().warning("Board '" + name + "' could not be shown by packets; it keeps its item frames.");
                        if (board.maps.size() != board.width * board.height) {
                            World world = Bukkit.getWorld(board.world);
                            if (world == null) throw new IllegalArgumentException("world '" + board.world + "' is not loaded");
                            List<Integer> maps = new ArrayList<>();
                            for (int i = 0; i < board.width * board.height; i++) maps.add(Bukkit.createMap(world).getId());
                            section.set(name + ".maps", maps);
                            try { core.data().files().write(boardsFile.toPath(), yaml); }
                            catch (IOException failure) {
                                section.set(name + ".maps", board.maps);
                                throw new IllegalStateException("Could not save the new map ids to boards.yml", failure);
                            }
                            board = board.withMaps(maps);
                            runtime = new BoardRuntime(board, this::sign, this::compose, checkTicks(board), personal);
                        }
                        for (int i = 0; i < board.maps.size(); i++) attach(Bukkit.getMap(board.maps.get(i)), runtime, i);
                        restoreFrames(board, null);
                    }
                    boards.put(name.toLowerCase(Locale.ROOT), runtime);
                    for (Board.Top top : board.tops()) {
                        String kind = Leaderboards.kind(top.source());
                        if (kind.isEmpty()) { getLogger().warning("Board '" + name + "': unknown ranking source '" + top.source() + "' (use stat:, placeholder:, objective: or minigames:)"); continue; }
                        rankingTops.putIfAbsent(top.source(), top);
                        if (kind.equals("minigames")) courseSources.add(top.source());
                        else if (!kind.equals("objective")) sampled.add(top.source());
                    }
                    for (Board.Click click : board.clicks) {
                        if (click.vote().isBlank()) continue;
                        String[] target = Polls.target(click.vote());
                        if (target == null) getLogger().warning("Board '" + name + "': vote '" + click.vote() + "' must look like poll:option (a-z, 0-9, _ and -)");
                        else boardPolls.add(target[0]);
                    }
                } catch (RuntimeException broken) {
                    getLogger().log(Level.WARNING, "Board '" + name + "' in boards.yml is invalid and was skipped", broken);
                }
            }
        }
        if (packetMode && packets.count() > 0) tasks.timer(1, 1, packets::tick);
        minigames.retain(courseSources);
        if (rankingsOn && (!sampled.isEmpty() || !courseSources.isEmpty())) {
            int seconds = Math.clamp(getConfig().getInt("leaderboards.sample-seconds", 60), 5, 3600);
            tasks.timer(40, seconds * 20L, this::sample);
            seed();
        }
        // help and tab completion ask the switches live; only send online players their command list again
        if (before != null && !before.equals(switches)) {
            try { core.command().refresh(); }
            catch (RuntimeException failure) { getLogger().log(Level.WARNING, "Command list not sent again", failure); }
        }
    }

    // ------------------------------------------------------------------ render mode (item frames or Core packets)

    /**
     * render-mode: auto (packets when Vystorm Core can show client-only map walls on this server, else item frames),
     * packets (same, with a warning when it falls back) or entities (always real item frames and maps).
     */
    private void resolveRenderMode() {
        renderMode = getConfig().getString("render-mode", "auto").trim().toLowerCase(Locale.ROOT);
        if (!List.of("auto", "packets", "entities").contains(renderMode)) {
            getLogger().warning("render-mode '" + renderMode + "' is unknown (auto, packets or entities); using auto");
            renderMode = "auto";
        }
        packetMode = false;
        packetUnavailable = null;
        // packet-mode.enabled: false works like render-mode: entities
        if (renderMode.equals("entities") || !on(Feature.PACKETS)) return;
        packetUnavailable = PacketBoards.unavailable(core.packages().clientDisplays());
        if (packetUnavailable == null) {
            if (packets == null) packets = new PacketBoards(this, core.packages().clientDisplays());
            packetMode = true;
        }
        if (!packetMode && renderMode.equals("packets"))
            getLogger().warning("render-mode: packets is not available (" + packetUnavailable + "); boards use item frames.");
    }

    /** Spots of the board's frames in loaded chunks: the frames found there (tile index → frames). */
    private Map<Integer, List<ItemFrame>> frames(Board board, org.bukkit.Chunk only) {
        Map<Integer, List<ItemFrame>> found = new HashMap<>();
        World world = Bukkit.getWorld(board.world);
        if (world == null) return found;
        for (int i = 0; i < board.width * board.height; i++) {
            int[] at = Geometry.tile(board.x, board.y, board.z, board.facingX, board.facingZ, i % board.width, i / board.width);
            if (only != null && (only.getX() != at[0] >> 4 || only.getZ() != at[2] >> 4)) continue;
            if (!world.isChunkLoaded(at[0] >> 4, at[2] >> 4) || !world.getChunkAt(at[0] >> 4, at[2] >> 4).isEntitiesLoaded()) continue;
            List<ItemFrame> here = new ArrayList<>();
            // frames holding maps have a full-block box that reaches into the neighbours: match the frame's own block
            for (Entity entity : world.getNearbyEntities(org.bukkit.util.BoundingBox.of(world.getBlockAt(at[0], at[1], at[2])).expand(0.05), e -> e instanceof ItemFrame)) {
                var spot = entity.getLocation();
                if (entity.isValid() && spot.getBlockX() == at[0] && spot.getBlockY() == at[1] && spot.getBlockZ() == at[2]
                        && board.name.equals(entity.getPersistentDataContainer().get(boardKey, PersistentDataType.STRING))) here.add((ItemFrame) entity);
            }
            found.put(i, here);
        }
        return found;
    }

    /** Packet mode: removes the board's real item frames (in loaded chunks, or in {@code only}). */
    private int removeFrames(Board board, org.bukkit.Chunk only) {
        int removed = 0;
        for (List<ItemFrame> here : frames(board, only).values()) for (ItemFrame frame : here) { frame.remove(); removed++; }
        if (removed > 0) getLogger().info("Board '" + board.name + "' runs as packets: removed " + removed + " item frames (they come back with render-mode: entities).");
        return removed;
    }

    /** Entity mode: hangs missing item frames again (boards that ran as packets before), in loaded chunks or {@code only}. */
    private void restoreFrames(Board board, org.bukkit.Chunk only) {
        if (board.maps.size() != board.width * board.height) return;
        World world = Bukkit.getWorld(board.world);
        if (world == null) return;
        BlockFace face = PacketBoards.face(board.facingX, board.facingZ);
        int restored = 0;
        for (var entry : frames(board, only).entrySet()) {
            if (!entry.getValue().isEmpty()) continue;
            int i = entry.getKey();
            int[] at = Geometry.tile(board.x, board.y, board.z, board.facingX, board.facingZ, i % board.width, i / board.width);
            MapView view = Bukkit.getMap(board.maps.get(i));
            if (view == null) continue;
            hang(world, at, face, view, board.glow, board.name);
            restored++;
        }
        if (restored > 0) getLogger().info("Board '" + board.name + "': hung " + restored + " item frames again (render mode: entities).");
    }

    private void hang(World world, int[] at, BlockFace face, MapView view, boolean glow, String name) {
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) map.getItemMeta();
        meta.setMapView(view);
        map.setItemMeta(meta);
        Class<? extends ItemFrame> type = glow ? GlowItemFrame.class : ItemFrame.class;
        world.spawn(new org.bukkit.Location(world, at[0], at[1], at[2]), type, frame -> {
            frame.setFacingDirection(face, true);
            frame.setItem(map, false);
            frame.setFixed(true);
            frame.setVisible(false);
            frame.setInvulnerable(true);
            frame.setSilent(true);
            frame.getPersistentDataContainer().set(boardKey, PersistentDataType.STRING, name);
        });
    }

    /** Chunks loading later: drop board frames in packet mode, re-hang missing ones in entity mode (next tick). */
    @EventHandler
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent event) {
        org.bukkit.Chunk chunk = event.getChunk();
        List<BoardRuntime> here = new ArrayList<>();
        for (BoardRuntime runtime : boards.values()) {
            Board b = runtime.board;
            if (!b.world.equals(chunk.getWorld().getName())) continue;
            for (int i = 0; i < b.width * b.height; i++) {
                int[] at = Geometry.tile(b.x, b.y, b.z, b.facingX, b.facingZ, i % b.width, i / b.width);
                if (at[0] >> 4 == chunk.getX() && at[2] >> 4 == chunk.getZ()) { here.add(runtime); break; }
            }
        }
        if (here.isEmpty()) return;
        tasks.later(1, () -> {
            if (!chunk.isLoaded()) return;
            for (BoardRuntime runtime : here) {
                if (boards.get(runtime.board.name.toLowerCase(Locale.ROOT)) != runtime) continue; // reloaded meanwhile
                if (packetMode && packets.has(runtime.board.name.toLowerCase(Locale.ROOT))) removeFrames(runtime.board, chunk);
                else restoreFrames(runtime.board, chunk);
            }
        });
    }

    // ------------------------------------------------------------------ saved data (leaderboards.yml, polls.yml)

    /**
     * Reads boards.yml; null (with a console error) when it has a YAML error. Callers must then not write it, otherwise
     * every board in it would be lost; the file is left in place so the admin can fix it and run /boards reload.
     */
    private YamlConfiguration readBoards() {
        try { return readStrict(boardsFile); }
        catch (IOException | org.bukkit.configuration.InvalidConfigurationException broken) {
            getLogger().log(Level.SEVERE, "boards.yml could not be read; no boards are shown and /boards create|remove are refused"
                    + " so the file is not overwritten. Fix it and run /boards reload.", broken);
            return null;
        }
    }

    /** An empty configuration when the file does not exist; throws instead of returning empty when it is unreadable. */
    static YamlConfiguration readStrict(File file) throws IOException, org.bukkit.configuration.InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        if (file.exists()) yaml.load(file);
        return yaml;
    }

    /** Reads a data file; null when it does not exist or is broken (then it is kept as it is and never overwritten). */
    private YamlConfiguration readData(File file) {
        if (!file.exists()) return null;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            return yaml;
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException broken) {
            unreadable.add(file);
            getLogger().log(Level.SEVERE, file.getName() + " could not be read and will not be overwritten until it is fixed (restart after fixing)", broken);
            return null;
        }
    }

    private void saveData() {
        if (leaderboards.dirty() && !write(leaderboardsFile, leaderboards.save())) leaderboards.markDirty();
        if (polls.dirty() && !write(pollsFile, polls.save())) polls.markDirty();
        if (guestbooks != null && guestbooks.dirty() && !write(guestbooksFile, guestbooks.save())) guestbooks.markDirty();
    }

    private boolean write(File file, YamlConfiguration yaml) {
        if (file == null || unreadable.contains(file)) return false;
        try { core.data().files().write(file.toPath(), yaml); return true; }
        catch (IOException failure) { getLogger().log(Level.WARNING, "Could not save " + file.getName(), failure); return false; }
    }

    /** Saves changed rankings and votes a few seconds later (many changes in a row are written once). */
    private void scheduleSave() {
        if (savePending || !(leaderboards.dirty() || polls.dirty() || (guestbooks != null && guestbooks.dirty()))) return;
        savePending = true;
        tasks.later(100, () -> { savePending = false; saveData(); });
    }

    // ------------------------------------------------------------------ rankings

    /** Sorted ranking for a source: stored values for stat/placeholder sources, the live scoreboard for objectives. */
    List<Leaderboards.Ranked> ranking(String source, boolean lowest) {
        if (!rankingsOn) return List.of();
        return switch (Leaderboards.kind(source)) {
            case "stat", "placeholder" -> leaderboards.ranking(source, lowest);
            case "objective" -> objective(source, lowest);
            case "minigames" -> minigames == null ? List.of() : minigames.ranking(source, lowest);
            default -> List.of();
        };
    }

    private List<Leaderboards.Ranked> objective(String source, boolean lowest) {
        String key = source + lowest;
        long now = System.currentTimeMillis();
        Cached cached = objectiveCache.get(key);
        if (cached != null && now - cached.at() < 1000) return cached.ranking();
        List<Leaderboards.Entry> entries = new ArrayList<>();
        var manager = Bukkit.getScoreboardManager();
        Objective objective = manager == null ? null : manager.getMainScoreboard().getObjective(Leaderboards.argument(source));
        if (objective != null) {
            for (String entry : manager.getMainScoreboard().getEntries()) {
                if (entry.startsWith("#")) continue; // fake players used by datapacks
                Score score = objective.getScore(entry);
                if (score.isScoreSet()) entries.add(new Leaderboards.Entry(null, entry, score.getScore(), 0));
            }
        }
        List<Leaderboards.Ranked> ranking = Leaderboards.rank(entries, lowest);
        objectiveCache.put(key, new Cached(ranking, now));
        return ranking;
    }

    /** Takes the current values of all online players for the sampled sources. */
    private void sample() {
        long now = System.currentTimeMillis();
        if (!sampled.isEmpty()) for (Player player : Bukkit.getOnlinePlayers()) sample(player, now);
        minigames.sample(courseSources, id -> { Player online = Bukkit.getPlayer(id); return online == null ? null : online.getName(); });
        scheduleSave();
    }

    private void sample(Player player, long now) {
        for (String source : sampled) {
            Double value = value(player, source);
            if (value != null) leaderboards.put(source, player.getUniqueId(), player.getName(), value, now, maxPlayers);
        }
    }

    /** A player's value in a stat or placeholder source; null if it cannot be read (unknown statistic, no PlaceholderAPI). */
    private Double value(OfflinePlayer player, String source) {
        String argument = Leaderboards.argument(source);
        try {
            if (Leaderboards.kind(source).equals("stat")) return (double) statistic(player, argument);
            if (papi == null || !(player instanceof Player online)) return null;
            String resolved = (String) papi.invoke(null, online, argument);
            if (resolved == null || resolved.equals(argument) || !resolved.matches(".*[0-9].*")) return null;
            return Board.number(resolved);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (badSources.add(source)) getLogger().warning("Ranking source '" + source + "' cannot be read: " + failure);
            return null;
        }
    }

    /** {@code NAME} or {@code NAME:MATERIAL} / {@code NAME:ENTITY}, e.g. PLAY_ONE_MINUTE, MINE_BLOCK:DIAMOND_ORE, KILL_ENTITY:ZOMBIE. */
    private static int statistic(OfflinePlayer player, String argument) {
        String[] parts = argument.split(":", 2);
        Statistic statistic = Statistic.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
        return switch (statistic.getType()) {
            case UNTYPED -> player.getStatistic(statistic);
            case BLOCK, ITEM -> {
                Material material = parts.length < 2 ? null : Material.matchMaterial(parts[1].trim());
                if (material == null) throw new IllegalArgumentException(statistic + " needs a block or item, e.g. " + statistic + ":DIAMOND_ORE");
                yield player.getStatistic(statistic, material);
            }
            case ENTITY -> {
                if (parts.length < 2) throw new IllegalArgumentException(statistic + " needs a mob, e.g. " + statistic + ":ZOMBIE");
                yield player.getStatistic(statistic, EntityType.valueOf(parts[1].trim().toUpperCase(Locale.ROOT)));
            }
        };
    }

    /**
     * Reads the saved statistics of players who were offline when a stat source was first used (most recently seen first,
     * up to max-players), so playtime and kill rankings are complete from the start. The stats files are read and parsed
     * on an async thread (OfflinePlayer#getStatistic would load each file on the server thread); the values are put into
     * the rankings back on the server thread.
     */
    private void seed() {
        if (!seedOffline || seeding != null) return;
        List<String> pending = sampled.stream().filter(s -> Leaderboards.kind(s).equals("stat") && !leaderboards.seeded(s)).toList();
        if (pending.isEmpty()) return;
        BukkitTask[] self = new BukkitTask[1];
        self[0] = tasks.later(60, () -> {
            if (seeding != self[0]) return;
            Map<String, OfflineStats.Key> keys = new LinkedHashMap<>();
            for (String source : pending) {
                try { keys.put(source, OfflineStats.key(Leaderboards.argument(source))); }
                catch (RuntimeException failure) { if (badSources.add(source)) getLogger().warning("Ranking source '" + source + "' cannot be read: " + failure); }
            }
            List<OfflinePlayer> players = new ArrayList<>(List.of(Bukkit.getOfflinePlayers()));
            players.removeIf(p -> p.getName() == null || p.isOnline());
            players.sort(Comparator.comparingLong(OfflinePlayer::getLastPlayed).reversed());
            record Seed(UUID id, String name, long lastSeen) {}
            List<Seed> queue = players.subList(0, Math.min(players.size(), maxPlayers)).stream()
                    .map(p -> new Seed(p.getUniqueId(), p.getName(), p.getLastPlayed())).toList();
            java.nio.file.Path folder = Bukkit.getWorlds().isEmpty() ? null : OfflineStats.folder(Bukkit.getWorlds().getFirst().getWorldFolder().toPath());
            tasks.async(() -> {
                record Found(String source, Seed player, long value) {}
                List<Found> found = new ArrayList<>();
                int unreadable = 0;
                if (folder != null && !keys.isEmpty()) {
                    for (Seed player : queue) {
                        try {
                            Map<OfflineStats.Key, Long> values = OfflineStats.read(folder, player.id(), keys.values());
                            for (var entry : keys.entrySet()) {
                                Long value = values.get(entry.getValue());
                                if (value != null && value > 0) found.add(new Found(entry.getKey(), player, value));
                            }
                        } catch (IOException failure) { unreadable++; }
                    }
                }
                int skipped = unreadable;
                if (!isEnabled()) return;
                try { Bukkit.getScheduler().runTask(this, () -> {
                    if (seeding != self[0]) return;
                    seeding = null;
                    for (Found f : found) leaderboards.put(f.source(), f.player().id(), f.player().name(), f.value(), f.player().lastSeen(), maxPlayers);
                    pending.forEach(leaderboards::markSeeded);
                    getLogger().info("Rankings: read saved statistics of " + queue.size() + " players for " + pending
                            + (skipped > 0 ? " (" + skipped + " stats files could not be read)" : ""));
                    boards.values().forEach(BoardRuntime::invalidate);
                    scheduleSave();
                }); } catch (org.bukkit.plugin.IllegalPluginAccessException disabledMeanwhile) { }
            });
        });
        seeding = self[0];
    }

    Lang lang() { return lang; }

    boolean playerMenuOn() { return rankingsOn && playerMenuOn && !rankingTops.isEmpty(); }

    /** Name of a ranking in chat and menus: the element's label or a readable form of the source. */
    String label(String source) {
        Board.Top top = rankingTops.get(source);
        return top != null && !top.label().isBlank() ? top.label() : Leaderboards.label(source);
    }

    /** The places of a player in every ranking in use (label → place), for the player menu and chat. */
    Map<String, Leaderboards.Ranked> places(String player) {
        Map<String, Leaderboards.Ranked> places = new LinkedHashMap<>();
        for (Board.Top top : rankingTops.values()) {
            Leaderboards.Ranked place = Leaderboards.find(ranking(top.source(), top.lowest()), player);
            if (place != null) places.put(top.source(), place);
        }
        return places;
    }

    String format(String source, double value) {
        Board.Top top = rankingTops.get(source);
        return Leaderboards.format(value, top == null ? "number" : top.format());
    }

    /** Sends a player's places in all rankings to {@code viewer} (player menu click). */
    void sendPlaces(CommandSender viewer, String player) {
        Map<String, Leaderboards.Ranked> places = places(player);
        lang.send(viewer, "rankings-header", "player", player);
        if (places.isEmpty()) lang.send(viewer, "rankings-none");
        for (var place : places.entrySet()) lang.send(viewer, "rankings-line", "name", label(place.getKey()), "rank", place.getValue().rank(),
                "value", format(place.getKey(), place.getValue().value()));
    }

    private void attach(MapView view, BoardRuntime runtime, int index) {
        if (view == null) return;
        for (var renderer : new ArrayList<>(view.getRenderers())) view.removeRenderer(renderer);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setLocked(true);
        BoardRuntime.Tile tile = runtime.tile(index);
        view.addRenderer(tile);
        tilesByMap.put(view.getId(), tile);
    }

    @EventHandler
    public void onMapLoad(MapInitializeEvent event) {
        int id = event.getMap().getId();
        if (tilesByMap.containsKey(id)) return;
        for (BoardRuntime runtime : boards.values()) {
            if (packetMode && packets.has(runtime.board.name.toLowerCase(Locale.ROOT))) continue;
            int index = runtime.board.maps.indexOf(id);
            if (index >= 0) { attach(event.getMap(), runtime, index); return; }
        }
    }

    // ------------------------------------------------------------------ content

    private int page(Board board, Player viewer, long now) {
        if (!on(Feature.PAGES)) return 0; // pages.enabled off: always the first page, no rotation
        return navigation.page(board, viewer == null ? null : viewer.getUniqueId(), now);
    }

    boolean guestbooksOn() { return on(Feature.BOARDS) && on(Feature.GUESTBOOKS); }

    private long guestbookWait(Player player, long now) {
        long cooldown = Math.clamp(getConfig().getLong("guestbooks.cooldown-seconds", 60), 0, 86_400) * 1000L;
        Long last = guestbookCooldowns.get(player.getUniqueId());
        return last == null || player.hasPermission("vystormboards.admin") ? 0 : Math.max(0, (last + cooldown - now + 999) / 1000);
    }

    /** {@code /boards guestbook [id] [remove N|clear]}: list books, show entries with remove buttons, or clean up. */
    void guestbook(CommandSender sender, String id, String action, String number) {
        if (off(sender, Feature.BOARDS, Feature.GUESTBOOKS)) return;
        if (id == null) {
            if (guestbooks.ids().isEmpty()) { lang.send(sender, "guestbook.none"); return; }
            for (String book : guestbooks.ids()) core.compat().platform().sendMessage(sender, lang.component(sender, "guestbook.list-entry", "book", book,
                    "count", guestbooks.entries(book).size()).clickEvent(ClickEvent.runCommand("/boards guestbook " + book)));
            return;
        }
        String book = id.toLowerCase(java.util.Locale.ROOT);
        if ("clear".equals(action)) { lang.send(sender, "guestbook.cleared", "book", book, "count", guestbooks.clear(book)); refreshGuestbook(book); return; }
        if ("remove".equals(action)) {
            int n;
            try { n = Integer.parseInt(number == null ? "" : number); } catch (NumberFormatException bad) { n = -1; }
            lang.send(sender, guestbooks.remove(book, n) ? "guestbook.removed" : "guestbook.no-entry", "book", book, "number", number == null ? "?" : number);
            refreshGuestbook(book);
            return;
        }
        var entries = guestbooks.entries(book);
        if (entries.isEmpty()) { lang.send(sender, "guestbook.empty-book", "book", book); return; }
        lang.send(sender, "guestbook.header", "book", book, "count", entries.size());
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            core.compat().platform().sendMessage(sender, lang.component(sender, "guestbook.line", "number", i + 1, "player", e.name(), "text", e.text())
                    .append(Component.space()).append(lang.component(sender, "guestbook.remove-button").clickEvent(ClickEvent.runCommand("/boards guestbook " + book + " remove " + (i + 1)))));
        }
    }

    private void refreshGuestbook(String book) {
        for (BoardRuntime shown : boards.values()) if (shown.board.mentions("{guestbook:" + book)) shown.invalidate();
        scheduleSave();
    }

    /**
     * Fills a board text for one viewer. Guestbook lines are written by players, so they are put in LAST: no later
     * pass (built-in tokens, countdowns, the clock, PlaceholderAPI) ever reads them, and {@link Guestbooks#clean}
     * already removed {@code <}, {@code >} and {@code §}, so they cannot carry MiniMessage either.
     */
    String placeholders(String text, Player viewer, Board board) {
        if (pollsOn) text = polls.apply(text, viewer == null ? null : viewer.getUniqueId());
        String out = text;
        if (on(Feature.PLACEHOLDERS)) out = out.replace("{online}", Integer.toString(Bukkit.getOnlinePlayers().size()))
                .replace("{max}", Integer.toString(Bukkit.getMaxPlayers()))
                .replace("{time}", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
                .replace("{date}", LocalDate.now().toString())
                .replace("{board}", board.name)
                .replace("{page}", Integer.toString(page(board, viewer, System.currentTimeMillis()) + 1))
                .replace("{pages}", Integer.toString(Math.max(1, board.pages.size())));
        if (on(Feature.COUNTDOWNS)) out = Countdown.apply(out, java.time.Instant.now(), java.time.ZoneId.systemDefault(),
                d -> lang.plain(viewer != null ? viewer : Bukkit.getConsoleSender(), "countdown-days", "days", Long.toString(d)));
        if (viewer != null) {
            if (on(Feature.PLACEHOLDERS)) out = out.replace("{player}", MiniMessage.miniMessage().escapeTags(viewer.getName())).replace("{world}", viewer.getWorld().getName());
            out = WorldClock.apply(out, !clockOff, () -> viewer.getWorld().getTime());
            if (papi != null && out.indexOf('%') >= 0) {
                try { out = (String) papi.invoke(null, viewer, out); } catch (ReflectiveOperationException | RuntimeException failure) { papi = null; }
            }
        } else if (!on(Feature.PER_PLAYER)) {
            // per-player.enabled off: one picture for everyone, so the viewer's own values stay blank
            out = WorldClock.apply(out.replace("{player}", "").replace("{world}", ""), false, () -> 0L);
        }
        if (guestbooks != null && guestbooksOn()) out = guestbooks.apply(out);
        return out;
    }

    private String sign(Board board, Player viewer) {
        long now = System.currentTimeMillis();
        int page = page(board, viewer, now);
        StringBuilder signature = new StringBuilder().append(page).append('\u0000');
        for (Board.Element element : board.visiblePage(page)) sign(signature, element, board, viewer, now);
        return signature.toString();
    }

    private void sign(StringBuilder signature, Board.Element element, Board board, Player viewer, long now) {
        switch (element) {
            case Board.Text text -> signature.append(placeholders(text.text(), viewer, board));
            case Board.Head head -> signature.append(assets.face(head.player().isBlank() && viewer != null ? viewer.getName() : head.player()) != null);
            case Board.Progress bar -> signature.append(placeholders(bar.value(), viewer, board)).append('/').append(placeholders(bar.max(), viewer, board));
            case Board.Image image -> signature.append(composer.frame(image.file(), now, fps));
            case Board.Gated gated -> {
                signature.append(viewer != null && allowed(viewer, gated.permission()));
                sign(signature, gated.element(), board, viewer, now);
            }
            case Board.Top top -> {
                for (Leaderboards.Ranked row : Composer.rows(ranking(top.source(), top.lowest()), top, viewer == null ? "" : viewer.getName())) {
                    signature.append(row.rank()).append(row.name()).append('=').append(row.value()).append(top.heads() && assets.face(row.name()) != null).append(';');
                }
            }
            default -> { }
        }
        signature.append('\u0000');
    }

    private static boolean allowed(Player viewer, String permission) { return Board.permitted(permission, viewer::hasPermission); }

    private byte[] compose(Board board, Player viewer) {
        long now = System.currentTimeMillis();
        int[] rgb = composer.render(board, text -> placeholders(text, viewer, board), viewer == null ? "" : viewer.getName(),
                permission -> viewer != null && viewer.hasPermission(permission), now, page(board, viewer, now));
        return colors.convert(rgb);
    }

    int[] editorPixels(Board board, Player viewer, int page) {
        return composer.render(board, text -> placeholders(text.replace("{page}", Integer.toString(page + 1)), viewer, board), viewer.getName(), viewer::hasPermission,
                System.currentTimeMillis(), page);
    }

    /** How often a board is checked: its refresh, faster while pages turn or images move. */
    private int checkTicks(Board board) {
        int ticks = board.refreshTicks;
        if (!board.pages.isEmpty() && on(Feature.PAGES)) ticks = Math.min(ticks, Math.max(20, board.pageSeconds * 20 / 2));
        if (!on(Feature.IMAGES) || !on(Feature.ANIMATIONS)) return ticks;
        List<Board.Element> all = new ArrayList<>(board.elements);
        board.pages.forEach(all::addAll);
        for (Board.Element element : all) {
            Board.Element inner = element instanceof Board.Gated gated ? gated.element() : element;
            if (inner instanceof Board.Image image && assets.frames(image.file()) > 1) ticks = Math.min(ticks, Math.max(2, 20 / fps));
        }
        return ticks;
    }

    // ------------------------------------------------------------------ frames and clicks

    private BoardRuntime boardOf(Entity entity) {
        if (!(entity instanceof ItemFrame frame)) return null;
        String name = frame.getPersistentDataContainer().get(boardKey, PersistentDataType.STRING);
        return name == null ? null : boards.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * The frames of a board go through Core's stages, so Core decides when they run among all Vystorm plugins. Nobody
     * breaks, turns, empties or hits a frame of a board (PROTECT): that also keeps the protections of other Vystorm
     * plugins quiet, which skip a cancelled event. The click on the board is the plugin's own feature (ACT); it takes
     * the cancelled event, because the board itself cancelled it and a board is clickable in protected places too.
     */
    void handleSharedEvents(EventStages stages) {
        stages.handle(this, HangingBreakEvent.class, EventStage.PROTECT, this::onBreak);
        stages.handle(this, PlayerInteractEntityEvent.class, EventStage.PROTECT, this::onFrameUse);
        stages.handle(this, PlayerInteractEntityEvent.class, EventStage.ACT, true, this::onRightClick);
        stages.handle(this, EntityDamageByEntityEvent.class, EventStage.PROTECT, this::onFrameHit);
        stages.handle(this, EntityDamageByEntityEvent.class, EventStage.ACT, true, this::onLeftClick);
        stages.handle(this, PlayerAnimationEvent.class, EventStage.ACT, true, this::onArmSwing);
    }

    private boolean boardFrame(Entity entity) {
        return entity instanceof ItemFrame frame && frame.getPersistentDataContainer().has(boardKey, PersistentDataType.STRING);
    }

    /** frame-protection.enabled: board frames cannot be broken, hit, turned or emptied (also while boards.enabled is off). */
    private boolean protectedFrame(Entity entity) { return on(Feature.FRAME_PROTECTION) && boardFrame(entity); }

    /** Stage PROTECT, registered in {@link #handleSharedEvents}. */
    public void onBreak(HangingBreakEvent event) {
        if (protectedFrame(event.getEntity())) event.setCancelled(true);
    }

    /** Stage PROTECT, registered in {@link #handleSharedEvents}: the map stays in the frame and is not turned. */
    public void onFrameUse(PlayerInteractEntityEvent event) {
        if (protectedFrame(event.getRightClicked())) event.setCancelled(true);
    }

    /** Stage ACT, registered in {@link #handleSharedEvents} for cancelled clicks too: the right-click on a board. */
    public void onRightClick(PlayerInteractEntityEvent event) {
        if (!boardFrame(event.getRightClicked())) return;
        if (on(Feature.FRAME_PROTECTION)) event.setCancelled(true);
        if (event.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) click(event.getPlayer(), boardOf(event.getRightClicked()));
    }

    /** Stage PROTECT, registered in {@link #handleSharedEvents}: the frame takes no damage. */
    public void onFrameHit(EntityDamageByEntityEvent event) {
        if (protectedFrame(event.getEntity())) event.setCancelled(true);
    }

    /** Stage ACT, registered in {@link #handleSharedEvents} for cancelled hits too: the left-click on a board. */
    public void onLeftClick(EntityDamageByEntityEvent event) {
        if (!boardFrame(event.getEntity())) return;
        if (on(Feature.FRAME_PROTECTION)) event.setCancelled(true);
        if (event.getDamager() instanceof Player player) click(player, boardOf(event.getEntity()));
    }

    /** Extra reach for the arm swing: the server knows the player's place a little later than the client aimed. */
    private static final double SWING_TOLERANCE = 0.25;

    /**
     * Stage ACT, registered in {@link #handleSharedEvents} for cancelled swings too: the left-click on a board that
     * hangs as real item frames. A hit on a fixed frame raises no damage event, so the click is read from the arm
     * swing: the board frame must be the first thing in the player's line of sight within reach. A swing that follows
     * a click already handled (right click, a hit that did raise a damage event) is stopped by the click guard.
     */
    public void onArmSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player player = event.getPlayer();
        Long dropped = lastDrop.get(player.getUniqueId());
        if (dropped != null && System.currentTimeMillis() - dropped < 150) return;
        BoardRuntime runtime = aimedAt(player);
        if (runtime != null) click(player, runtime);
    }

    /** Dropping an item swings the arm as well (the drop arrives first): remembered so it is not taken for a click. */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) { lastDrop.put(event.getPlayer().getUniqueId(), System.currentTimeMillis()); }

    /** The item-frame board whose frame the player looks at within reach, with nothing in front of it; else null. */
    private BoardRuntime aimedAt(Player player) {
        World world = player.getWorld();
        boolean any = false;
        for (BoardRuntime runtime : boards.values()) {
            if (!runtime.board.clicks.isEmpty() && runtime.board.world.equals(world.getName()) && !shownAsPackets(runtime)) { any = true; break; }
        }
        if (!any) return null;
        var eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        var hit = world.rayTraceEntities(eye, direction, reach(player) + SWING_TOLERANCE, entity -> entity != player);
        if (hit == null || !boardFrame(hit.getHitEntity())) return null;
        BoardRuntime runtime = boardOf(hit.getHitEntity());
        if (runtime == null || shownAsPackets(runtime)) return null;
        double distance = hit.getHitPosition().distance(eye.toVector());
        // the client aims at the block outline in front of a frame, not at the frame behind it
        if (distance > 1e-6 && world.rayTraceBlocks(eye, direction, distance, org.bukkit.FluidCollisionMode.NEVER, false) != null) return null;
        return runtime;
    }

    private boolean shownAsPackets(BoardRuntime runtime) {
        return packetMode && packets != null && packets.has(runtime.board.name.toLowerCase(Locale.ROOT));
    }

    /** How far the player reaches for entities: the attribute where the server has it, else the vanilla values. */
    private double reach(Player player) {
        try {
            var range = player.getAttribute(core.compat().server().attribute("ENTITY_INTERACTION_RANGE", "PLAYER_ENTITY_INTERACTION_RANGE"));
            if (range != null) return range.getValue();
        } catch (RuntimeException | LinkageError unknown) { /* older server or no attribute: vanilla values below */ }
        return player.getGameMode() == org.bukkit.GameMode.CREATIVE ? 5 : 3;
    }

    private void click(Player player, BoardRuntime runtime) {
        if (runtime == null || runtime.board.clicks.isEmpty()) return;
        long now = System.currentTimeMillis();
        Long last = lastClick.get(player.getUniqueId());
        if (last != null && now - last < 300) return;
        lastClick.put(player.getUniqueId(), now);
        if (off(player, Feature.CLICKS)) return;
        Board board = runtime.board;
        var eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        int[] pixel = Geometry.pixel(board.x, board.y, board.z, board.facingX, board.facingZ, board.width, board.height,
                new double[]{eye.getX(), eye.getY(), eye.getZ()}, new double[]{direction.getX(), direction.getY(), direction.getZ()});
        if (pixel == null) return;
        int index = board.clickAtPage(pixel[0], pixel[1], page(board, player, now), player::hasPermission);
        if (index < 0) return;
        Board.Click area = board.clicks.get(index);
        String key = board.name + "#" + index;
        long wait = cooldowns.remaining(player.getUniqueId(), key, now);
        if (wait > 0) { lang.send(player, "click-cooldown", "seconds", Long.toString(wait)); return; }
        if (!area.navigate().isBlank()) {
            if (!navigation.enabled()) { lang.send(player, "feature-off"); return; }
            for (Feature needed : List.of(Feature.PAGES, Feature.PER_PLAYER)) {
                if (!on(needed)) { lang.send(player, "feature-needs", "switch", needed.path, "name", lang.plain(player, needed.label())); return; }
            }
            if (!navigation.navigate(board, player.getUniqueId(), area.navigate(), now)) {
                lang.send(player, "navigation-invalid");
                return;
            }
            runtime.forget(player.getUniqueId());
            cooldowns.start(player.getUniqueId(), key, now, area.cooldown());
            lang.send(player, area.navigate().equals("auto") ? "navigation-auto" : "navigation-page",
                    "page", Integer.toString(page(board, player, now) + 1), "pages", Integer.toString(board.pages.size()));
            return;
        }
        if (!area.guestbook().isBlank()) {
            if (!guestbooksOn()) { lang.send(player, "feature-off"); return; }
            if (unreadable.contains(guestbooksFile)) { lang.send(player, "failed"); return; }
            String book = area.guestbook();
            if (!Guestbooks.ID.matcher(book).matches()) { lang.send(player, "poll-bad-id"); return; }
            long bookWait = guestbookWait(player, now);
            if (bookWait > 0) { lang.send(player, "guestbook.cooldown", "seconds", Long.toString(bookWait)); return; }
            cooldowns.start(player.getUniqueId(), key, now, area.cooldown());
            core.dialogs().prompt(this, player, lang.ref("guestbook.title"), lang.ref("guestbook.question"), "", text -> {
                if (!player.isOnline()) return;
                long at = System.currentTimeMillis();
                if (guestbookWait(player, at) > 0) return;
                if (!guestbooks.add(book, player.getUniqueId(), player.getName(), text, at)) { lang.send(player, "guestbook.empty"); return; }
                guestbookCooldowns.put(player.getUniqueId(), at);
                lang.send(player, "guestbook.written");
                for (BoardRuntime shown : boards.values()) if (shown.board.mentions("{guestbook:" + book)) shown.invalidate();
                scheduleSave();
            }, () -> {}, Player::isOnline);
            return;
        }
        boolean runCommand = true;
        String[] vote = null;
        if (!area.vote().isBlank()) {
            if (!pollsOn) { lang.send(player, "feature-off"); return; }
            if (unreadable.contains(pollsFile)) { lang.send(player, "failed"); return; }
            vote = Polls.target(area.vote());
            if (vote == null) { lang.send(player, "poll-bad-id"); return; }
        }
        if (vote != null) {
            Polls.Result result = polls.vote(vote[0], vote[1], player.getUniqueId(), allowChange);
            switch (result) {
                case CLOSED -> { lang.send(player, "vote-closed", "poll", vote[0]); return; }
                case SAME -> { lang.send(player, "vote-same", "option", vote[1]); return; }
                case LOCKED -> { lang.send(player, "vote-locked", "option", polls.choice(vote[0], player.getUniqueId())); return; }
                case CAST, CHANGED -> {
                    if (area.message().isBlank()) lang.send(player, result == Polls.Result.CAST ? "vote-cast" : "vote-changed", "option", vote[1], "poll", vote[0]);
                    // commands (e.g. a reward) only run for the first vote, not when switching options
                    runCommand = result == Polls.Result.CAST;
                    for (BoardRuntime shown : boards.values()) {
                        if (shown.board.mentions("{votes:" + vote[0]) || shown.board.mentions("{vote:" + vote[0])) shown.invalidate();
                    }
                    scheduleSave();
                }
            }
        }
        cooldowns.start(player.getUniqueId(), key, now, area.cooldown());
        if (!area.message().isBlank()) core.compat().platform().sendMessage(player, MiniMessage.miniMessage().deserialize(placeholders(area.message(), player, board)));
        if (runCommand && !area.command().isBlank()) {
            String command = area.command().replace("{player}", player.getName());
            if (command.startsWith("/")) command = command.substring(1);
            if (area.console()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            else player.performCommand(command);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) { assets.forgetFace(event.getPlayer().getUniqueId(), event.getPlayer().getName()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (rankingsOn && !sampled.isEmpty()) { sample(event.getPlayer(), System.currentTimeMillis()); scheduleSave(); }
        lastClick.remove(id);
        lastDrop.remove(id);
        navigation.forget(id);
        cooldowns.prune(id, System.currentTimeMillis());
        for (BoardRuntime runtime : boards.values()) runtime.forget(id);
        for (BoardRuntime.Tile tile : tilesByMap.values()) tile.forget(id);
        if (packets != null) packets.forget(id);
    }

    // ------------------------------------------------------------------ commands

    /**
     * {@code /boards} as a Vystorm Core command tree: names, aliases, usage and help come from {@code commands.boards.*}
     * in the language files (e.g. {@code /anschlagtafeln liste}, {@code /公告板 列表}); English always works.
     * {@code p} may be {@code null} in tests that only inspect names and help.
     */
    static CommandSpec commandSpec(BoardsPlugin p) {
        return CommandSpec.command("boards")
                .permission("vystormboards.admin")
                .executes(ctx -> { if (ctx.sender() instanceof org.bukkit.entity.Player player && !p.editorOff) p.editor.open(player, null); else p.list(ctx.sender()); })
                .sub("edit", s -> gate(s, p, Feature.BOARDS, Feature.EDITOR).playerOnly().arg(Arg.word("name").optional().suggests((ctx, prefix) -> new ArrayList<>(p.boards.keySet())))
                        .executes(ctx -> p.edit(ctx.player(), ctx.get("name"))))
                .sub("create", s -> gate(s, p, Feature.BOARDS)
                        .arg(Arg.word("name"))
                        .arg(Arg.word("width").suggests(List.of("1", "2", "3", "4")))
                        .arg(Arg.word("height").suggests(List.of("1", "2", "3")))
                        .arg(Arg.word("world").optional().suggests((ctx, prefix) -> Bukkit.getWorlds().stream().map(World::getName).toList()))
                        .arg(Arg.word("x").optional())
                        .arg(Arg.word("y").optional())
                        .arg(Arg.word("z").optional())
                        .arg(Arg.word("facing").optional().suggests(List.of("north", "east", "south", "west")))
                        .executes(ctx -> p.create(ctx.sender(), legacy(ctx, "create"))))
                .sub("info", s -> gate(s, p, Feature.BOARDS).arg(Arg.word("name").suggests((ctx, prefix) -> new ArrayList<>(p.boards.keySet())))
                        .executes(ctx -> p.info(ctx.sender(), legacy(ctx, "info"))))
                .sub("list", s -> gate(s, p, Feature.BOARDS).executes(ctx -> p.list(ctx.sender())))
                .sub("deadline", s -> gate(s, p, Feature.BOARDS, Feature.POLLS, Feature.POLL_DEADLINES).arg(Arg.word("id").suggests((ctx, prefix) -> new ArrayList<>(p.pollIds())))
                        .arg(Arg.word("time").optional().suggests(List.of("clear")))
                        .executes(ctx -> p.deadline(ctx.sender(), legacy(ctx, "deadline"))))
                .sub("poll", s -> gate(s, p, Feature.BOARDS, Feature.POLLS).arg(Arg.word("id").optional().suggests((ctx, prefix) -> new ArrayList<>(p.pollIds())))
                        .arg(Arg.choice("action", "close", "open", "reset").optional())
                        .arg(Arg.choice("confirm", "confirm").optional())
                        .executes(ctx -> p.poll(ctx.sender(), legacy(ctx, "poll"))))
                .sub("guestbook", s -> gate(s, p, Feature.BOARDS, Feature.GUESTBOOKS).arg(Arg.word("id").optional().suggests((ctx, prefix) -> new ArrayList<>(p.guestbooks.ids())))
                        .arg(Arg.choice("action", "remove", "clear").optional())
                        .arg(Arg.word("number").optional())
                        .executes(ctx -> p.guestbook(ctx.sender(), ctx.get("id"), ctx.get("action"), ctx.get("number"))))
                .sub("photos", s -> gate(s, p, Feature.BOARDS, Feature.PHOTOS).arg(Arg.word("name").suggests((ctx, prefix) -> new ArrayList<>(p.boards.keySet())))
                        .arg(Arg.word("source").optional().suggests(List.of("gallery", "newest", "player:")))
                        .executes(ctx -> p.photos(ctx.sender(), ctx.get("name"), ctx.get("source"))))
                .sub("reload", s -> s.executes(ctx -> p.reload(ctx.sender())))
                .sub("remove", s -> gate(s, p, Feature.BOARDS).arg(Arg.word("name").suggests((ctx, prefix) -> new ArrayList<>(p.boards.keySet())))
                        .executes(ctx -> p.remove(ctx.sender(), legacy(ctx, "remove"))))
                .sub("render", s -> gate(s, p, Feature.BOARDS, Feature.RENDER_INFO).executes(ctx -> p.render(ctx.sender())))
                .sub("top", s -> gate(s, p, Feature.BOARDS, Feature.RANKINGS).arg(Arg.word("source").optional().suggests((ctx, prefix) -> new ArrayList<>(p.rankingTops.keySet())))
                        .arg(Arg.choice("action", "reset").optional())
                        .arg(Arg.choice("confirm", "confirm").optional())
                        .executes(ctx -> p.top(ctx.sender(), legacy(ctx, "top"))))
                .build();
    }

    /**
     * Keeps a subcommand out of help and tab completion while one of its switches is off (asked at every completion,
     * so a switch applies at once; the command still answers the off message).
     */
    static CommandSpec.Builder gate(CommandSpec.Builder sub, BoardsPlugin p, Feature... features) {
        if (p == null) return sub;
        Feature[] needed = features.clone();
        return sub.visibleWhen(() -> {
            for (Feature feature : needed) if (!p.on(feature)) return false;
            return true;
        });
    }

    /** The old argument array ({@code sub} first, then the typed arguments; keywords as their English keys). */
    static String[] legacy(CommandContext ctx, String sub) {
        List<String> out = new ArrayList<>();
        out.add(sub);
        for (Arg<?> arg : ctx.node().args()) {
            Object value = ctx.get(arg.id());
            if (value == null) break;
            out.add(value.toString());
        }
        return out.toArray(String[]::new);
    }

    /** {@code /boards edit [NAME]}: the page editor, unless it is switched off (page-editor.enabled). */
    void edit(Player player, String name) {
        if (editorOff) { lang.send(player, "feature-off"); return; }
        if (off(player, Feature.BOARDS, Feature.EDITOR)) return;
        editor.open(player, name);
    }

    void reload(CommandSender sender) {
        load();
        if (configProblem != null) { lang.send(sender, "config-broken", "file", "config.yml", "problem", configProblem); return; }
        lang.send(sender, "reloaded", "count", Integer.toString(boards.size()));
    }

    void list(CommandSender sender) {
        if (off(sender, Feature.BOARDS)) return;
        lang.send(sender, "list-header", "count", Integer.toString(boards.size()));
        for (BoardRuntime runtime : boards.values()) {
            Board b = runtime.board;
            lang.send(sender, "list-entry", "name", b.name, "size", b.width + "x" + b.height, "where", b.world + " " + b.x + " " + b.y + " " + b.z);
        }
    }

    /** Clickable chat button that runs {@code command}. */
    private Component button(CommandSender sender, String key, String command) {
        return lang.component(sender, key).clickEvent(ClickEvent.runCommand(command));
    }

    /** /boards top – rankings in use; /boards top SOURCE [reset] – first places, or forget the stored values. */
    void top(CommandSender sender, String[] args) {
        if (!rankingsOn) { lang.send(sender, "feature-off"); return; }
        if (args.length < 2) {
            lang.send(sender, "top-list-header", "count", rankingTops.size());
            for (Board.Top top : rankingTops.values()) {
                core.compat().platform().sendMessage(sender, lang.component(sender, "top-list-entry", "name", label(top.source()), "source", top.source(),
                        "players", ranking(top.source(), top.lowest()).size()).clickEvent(ClickEvent.runCommand("/boards top " + top.source())));
            }
            return;
        }
        String source = args[1];
        if (Leaderboards.kind(source).isEmpty()) { lang.send(sender, "top-unknown", "source", source); return; }
        if (args.length > 2 && args[2].equalsIgnoreCase("reset")) {
            if (unreadable.contains(leaderboardsFile)) { lang.send(sender, "failed"); return; }
            if (Leaderboards.kind(source).equals("objective")) { lang.send(sender, "top-reset-objective"); return; }
            if (Leaderboards.kind(source).equals("minigames")) { lang.send(sender, "top-reset-minigames"); return; }
            if (args.length < 4 || !args[3].equalsIgnoreCase("confirm")) {
                core.compat().platform().sendMessage(sender, lang.component(sender, "top-reset-ask", "source", source).append(Component.space())
                        .append(button(sender, "button-confirm", "/boards top " + source + " reset confirm")));
                return;
            }
            leaderboards.clear(source);
            scheduleSave();
            if (seeding == null) seed();
            boards.values().forEach(BoardRuntime::invalidate);
            lang.send(sender, "top-reset", "source", source);
            return;
        }
        Board.Top top = rankingTops.get(source);
        boolean lowest = top != null && top.lowest();
        List<Leaderboards.Ranked> ranking = ranking(source, lowest);
        lang.send(sender, "top-header", "name", label(source), "source", source, "players", ranking.size());
        if (ranking.isEmpty()) lang.send(sender, "top-empty");
        for (Leaderboards.Ranked row : ranking.subList(0, Math.min(10, ranking.size()))) {
            lang.send(sender, "top-entry", "rank", row.rank(), "name", row.name(), "value", format(source, row.value()));
        }
    }

    /** Shows or changes the deadline of an existing poll, in the server time zone. */
    void deadline(CommandSender sender, String[] args) {
        if (!pollsOn) { lang.send(sender, "feature-off"); return; }
        if (off(sender, Feature.POLL_DEADLINES)) return;
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!Polls.ID.matcher(id).matches()) { lang.send(sender, "poll-bad-id"); return; }
        if (!polls.exists(id) && !boardPolls.contains(id)) { lang.send(sender, "poll-unknown", "poll", id); return; }
        if (args.length > 2) {
            if (unreadable.contains(pollsFile)) { lang.send(sender, "failed"); return; }
            long millis = 0;
            if (!args[2].equalsIgnoreCase("clear")) {
                java.time.Instant target = Countdown.parse(args[2], java.time.ZoneId.systemDefault());
                if (target == null || !target.isAfter(java.time.Instant.now())) { lang.send(sender, "poll-deadline-invalid"); return; }
                try { millis = target.toEpochMilli(); }
                catch (ArithmeticException tooFar) { lang.send(sender, "poll-deadline-invalid"); return; }
            }
            polls.deadline(id, millis);
            scheduleSave();
        }
        showDeadline(sender, id);
    }

    private void showDeadline(CommandSender sender, String id) {
        long millis = polls.deadline(id);
        if (millis == 0) lang.send(sender, "poll-deadline-none", "poll", id);
        else lang.send(sender, "poll-deadline", "poll", id, "time",
                java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toString());
    }

    /** /boards poll – all polls; /boards poll ID – results with buttons; ID close|open|reset [confirm]. */
    void poll(CommandSender sender, String[] args) {
        if (!pollsOn) { lang.send(sender, "feature-off"); return; }
        if (args.length < 2) {
            lang.send(sender, "poll-list-header", "count", pollIds().size());
            if (pollIds().isEmpty()) lang.send(sender, "poll-list-empty");
            for (String id : pollIds()) {
                core.compat().platform().sendMessage(sender, lang.component(sender, "poll-list-entry", "poll", id, "votes", polls.total(id),
                        "state", lang.plain(sender, polls.closed(id) ? "poll-state-closed" : "poll-state-open")).clickEvent(ClickEvent.runCommand("/boards poll " + id)));
            }
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!Polls.ID.matcher(id).matches()) { lang.send(sender, "poll-bad-id"); return; }
        String action = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
        if (!action.isEmpty() && unreadable.contains(pollsFile)) { lang.send(sender, "failed"); return; }
        switch (action) {
            case "close", "open" -> {
                boolean closing = action.equals("close");
                polls.setClosed(id, closing);
                scheduleSave();
                lang.send(sender, closing ? "poll-closed" : "poll-opened", "poll", id);
                return;
            }
            case "reset" -> {
                if (args.length < 4 || !args[3].equalsIgnoreCase("confirm")) {
                    core.compat().platform().sendMessage(sender, lang.component(sender, "poll-reset-ask", "poll", id, "votes", polls.total(id)).append(Component.space())
                            .append(button(sender, "button-confirm", "/boards poll " + id + " reset confirm")));
                    return;
                }
                int removed = polls.reset(id);
                scheduleSave();
                for (BoardRuntime shown : boards.values()) if (shown.board.mentions("{vote")) shown.invalidate();
                lang.send(sender, "poll-reset", "poll", id, "votes", removed);
                return;
            }
            default -> { }
        }
        if (!polls.exists(id) && !boardPolls.contains(id)) { lang.send(sender, "poll-unknown", "poll", id); return; }
        lang.send(sender, "poll-header", "poll", id, "votes", polls.total(id),
                "state", lang.plain(sender, polls.closed(id) ? "poll-state-closed" : "poll-state-open"));
        showDeadline(sender, id);
        for (var result : polls.results(id).entrySet()) {
            int percent = polls.percent(id, result.getKey());
            lang.send(sender, "poll-option", "option", result.getKey(), "count", result.getValue(), "percent", percent, "bar", bar(percent));
        }
        core.compat().platform().sendMessage(sender, button(sender, polls.closed(id) ? "button-open" : "button-close", "/boards poll " + id + (polls.closed(id) ? " open" : " close"))
                .append(Component.space()).append(button(sender, "button-reset", "/boards poll " + id + " reset"))
                .append(Component.space()).append(button(sender, "button-refresh", "/boards poll " + id)));
    }

    /** /boards render – render mode in use and Core's packet counters. */
    void render(CommandSender sender) {
        if (off(sender, Feature.BOARDS, Feature.RENDER_INFO)) return;
        String active = packetMode ? "packets" : "entities";
        lang.send(sender, "render-status", "mode", active, "configured", renderMode,
                "reason", packetMode || packetUnavailable == null ? "" : packetUnavailable);
        if (!packetMode) return;
        Map<String, Object> stats = PacketBoards.stats(core.packages().clientDisplays());
        List<Object> values = new ArrayList<>();
        stats.forEach((key, value) -> { values.add(key); values.add(value); });
        lang.send(sender, "render-stats", values.toArray());
    }

    /** Polls on the boards and polls with votes, sorted. */
    Set<String> pollIds() {
        Set<String> ids = new java.util.TreeSet<>(boardPolls);
        ids.addAll(polls.ids());
        return ids;
    }

    /** Ten-step text bar for chat results. */
    static String bar(int percent) {
        int filled = Math.clamp(Math.round(percent / 10f), 0, 10);
        return "|".repeat(filled) + ".".repeat(10 - filled);
    }

    void create(CommandSender sender, String[] args) {
        if (off(sender, Feature.BOARDS)) return;
        Player player = sender instanceof Player p ? p : null;
        if (args.length < 4 || player == null && args.length < 9) { lang.send(sender, player == null ? "usage-create-console" : "usage-create"); return; }
        String name = args[1].toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_-]{1,32}")) { lang.send(sender, "bad-name"); return; }
        if (boards.containsKey(name)) { lang.send(sender, "exists", "name", name); return; }
        YamlConfiguration yaml = readBoards();
        if (yaml == null) { lang.send(sender, "failed"); return; }
        ConfigurationSection savedBoards = yaml.getConfigurationSection("boards");
        if (savedBoards != null && savedBoards.getKeys(false).stream().anyMatch(name::equalsIgnoreCase)) {
            lang.send(sender, "exists", "name", name); return;
        }
        int width, height;
        try { width = Integer.parseInt(args[2]); height = Integer.parseInt(args[3]); } catch (NumberFormatException wrong) { lang.send(sender, "usage-create"); return; }
        if (width < 1 || height < 1 || width > 16 || height > 16) { lang.send(sender, "bad-size"); return; }
        Block wall;
        BlockFace face;
        if (args.length >= 9) {
            // create <name> <w> <h> <world> <x> <y> <z> <facing>: the wall block and the side the board faces
            World target = Bukkit.getWorld(args[4]);
            if (target == null) { lang.send(sender, "usage-create-console"); return; }
            try {
                wall = target.getBlockAt(Integer.parseInt(args[5]), Integer.parseInt(args[6]), Integer.parseInt(args[7]));
                face = BlockFace.valueOf(args[8].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException wrong) { lang.send(sender, "usage-create-console"); return; }
        } else {
            wall = player.getTargetBlockExact(10);
            face = core.compat().platform().getTargetBlockFace(player, 10);
        }
        if (wall == null || face == null || face.getModY() != 0 || face.getModX() != 0 && face.getModZ() != 0) { lang.send(sender, "look-at-wall"); return; }
        int fx = face.getModX(), fz = face.getModZ();
        Block first = wall.getRelative(face);
        // the block you look at is the top-left corner
        for (int ty = 0; ty < height; ty++) for (int tx = 0; tx < width; tx++) {
            int[] at = Geometry.tile(first.getX(), first.getY(), first.getZ(), fx, fz, tx, ty);
            Block spot = wall.getWorld().getBlockAt(at[0], at[1], at[2]);
            Block behind = spot.getRelative(face.getOppositeFace());
            if (!spot.isPassable() || !behind.getType().isSolid() || !spot.getWorld().getNearbyEntities(org.bukkit.util.BoundingBox.of(spot).expand(-0.05), e -> e instanceof org.bukkit.entity.Hanging).isEmpty()) {
                lang.send(sender, "no-room", "x", Integer.toString(at[0]), "y", Integer.toString(at[1]), "z", Integer.toString(at[2]));
                return;
            }
        }
        World world = wall.getWorld();
        List<Integer> maps = new ArrayList<>();
        boolean glow = getConfig().getBoolean("defaults.glow", true);
        // packet mode needs neither item frames nor server maps; they are made if the board ever switches to entities
        if (!packetMode) for (int ty = 0; ty < height; ty++) for (int tx = 0; tx < width; tx++) {
            MapView view = Bukkit.createMap(world);
            maps.add(view.getId());
        }
        String base = "boards." + name;
        yaml.set(base + ".world", world.getName());
        yaml.set(base + ".x", first.getX());
        yaml.set(base + ".y", first.getY());
        yaml.set(base + ".z", first.getZ());
        yaml.set(base + ".facing", Board.facingName(fx, fz));
        yaml.set(base + ".width", width);
        yaml.set(base + ".height", height);
        yaml.set(base + ".glow", glow);
        yaml.set(base + ".background", getConfig().getString("defaults.background", "#252a33"));
        yaml.set(base + ".refresh-seconds", 5);
        yaml.set(base + ".maps", maps);
        yaml.set(base + ".elements", starter(width, height));
        yaml.set(base + ".clicks", List.of());
        if (!write(boardsFile, yaml)) { lang.send(sender, "failed"); return; }
        load();
        lang.send(sender, "created", "name", name, "size", width + "x" + height);
    }

    /** Starter content for a new board: logo, title, greeting with the viewer's face, online counter. */
    private static List<Map<String, Object>> starter(int width, int height) {
        int w = width * Geometry.TILE, h = height * Geometry.TILE;
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(new LinkedHashMap<>(Map.of("type", "rect", "x", 0, "y", 0, "width", w, "height", Math.max(18, h / 6), "color", "#e3b04b")));
        elements.add(new LinkedHashMap<>(Map.of("type", "text", "x", 0, "y", Math.max(1, h / 48), "size", Math.max(10, h / 9), "align", "center",
                "color", "#252a33", "shadow", false, "text", "<b>Vystorm Boards")));
        if (h >= 128) elements.add(new LinkedHashMap<>(Map.of("type", "head", "x", 12, "y", h / 6 + 14, "size", Math.min(64, h / 3))));
        elements.add(new LinkedHashMap<>(Map.of("type", "text", "x", h >= 128 ? Math.min(64, h / 3) + 24 : 8, "y", h / 6 + 16, "size", Math.max(10, h / 12),
                "text", "Hello <gold>{player}</gold>!<br><gray>{online}/{max} online · {time}")));
        int logo = Math.min(128, h - h / 6 - 12);
        if (w >= 256 && logo >= 48) elements.add(new LinkedHashMap<>(Map.of("type", "image", "file", "vystorm-logo.png",
                "x", w - logo - 10, "y", h / 6 + 6, "width", logo, "height", logo)));
        return elements;
    }

    void info(CommandSender sender, String[] args) {
        if (off(sender, Feature.BOARDS)) return;
        BoardRuntime runtime = args.length > 1 ? boards.get(args[1].toLowerCase(Locale.ROOT)) : null;
        if (runtime == null) { lang.send(sender, "unknown-board", "name", args.length > 1 ? args[1] : ""); return; }
        Board b = runtime.board;
        lang.send(sender, "info", "name", b.name, "size", b.width + "x" + b.height, "pixels", b.pixelWidth() + "×" + b.pixelHeight(),
                "where", b.world + " " + b.x + " " + b.y + " " + b.z + " " + Board.facingName(b.facingX, b.facingZ),
                "elements", Integer.toString(b.elements.size()), "pages", Integer.toString(b.pages.size()), "clicks", Integer.toString(b.clicks.size()),
                "mode", lang.plain(sender, b.perPlayer() ? "info-per-player" : "info-shared"), "maps", b.maps.toString());
    }

    /** {@code /boards photos NAME [gallery|newest|player:NAME]}: the board becomes a slideshow of Snapshot photos. */
    void photos(CommandSender sender, String rawName, String rawSource) {
        if (photosOff) { lang.send(sender, "feature-off"); return; }
        if (off(sender, Feature.BOARDS)) return;
        String name = rawName == null ? "" : rawName.toLowerCase(Locale.ROOT);
        BoardRuntime runtime = boards.get(name);
        if (runtime == null) { lang.send(sender, "unknown-board", "name", name); return; }
        String source = rawSource == null || rawSource.isBlank() ? "gallery" : rawSource.strip();
        String file = SnapshotPhotos.PREFIX + source;
        if (!source.equalsIgnoreCase("gallery") && !source.equalsIgnoreCase("newest") && !(source.toLowerCase(Locale.ROOT).startsWith("player:") && source.length() > 7)) {
            lang.send(sender, "photos-usage"); return;
        }
        YamlConfiguration yaml = readBoards();
        if (yaml == null) { lang.send(sender, "failed"); return; }
        String base = "boards." + runtime.board.name;
        yaml.set(base + ".elements", List.of(new LinkedHashMap<>(Map.of("type", "image", "file", file, "x", 0, "y", 0,
                "width", runtime.board.pixelWidth(), "height", runtime.board.pixelHeight()))));
        yaml.set(base + ".pages", null);
        if (!write(boardsFile, yaml)) { lang.send(sender, "failed"); return; }
        load();
        lang.send(sender, "photos-set", "name", name, "source", source);
    }

    void remove(CommandSender sender, String[] args) {
        if (off(sender, Feature.BOARDS)) return;
        if (args.length < 2) { lang.send(sender, "usage-remove"); return; }
        String name = args[1].toLowerCase(Locale.ROOT);
        BoardRuntime runtime = boards.get(name);
        if (runtime == null) { lang.send(sender, "unknown-board", "name", name); return; }
        Board board = runtime.board;
        YamlConfiguration yaml = readBoards();
        if (yaml == null) { lang.send(sender, "failed"); return; }
        yaml.set("boards." + board.name, null);
        if (!write(boardsFile, yaml)) { lang.send(sender, "failed"); return; }
        World world = Bukkit.getWorld(board.world);
        int removed = 0;
        if (world != null) {
            for (int ty = 0; ty < board.height; ty++) for (int tx = 0; tx < board.width; tx++) {
                int[] at = Geometry.tile(board.x, board.y, board.z, board.facingX, board.facingZ, tx, ty);
                Block spot = world.getBlockAt(at[0], at[1], at[2]);
                for (Entity entity : world.getNearbyEntities(org.bukkit.util.BoundingBox.of(spot).expand(0.05), e -> e instanceof ItemFrame)) {
                    if (entity.isValid() && board.name.equals(entity.getPersistentDataContainer().get(boardKey, PersistentDataType.STRING))) { entity.remove(); removed++; }
                }
            }
        }
        load();
        lang.send(sender, "removed", "name", name, "frames", Integer.toString(removed));
    }

}
