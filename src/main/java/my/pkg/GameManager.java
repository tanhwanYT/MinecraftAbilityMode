package my.pkg;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.event.inventory.InventoryCloseEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class GameManager implements Listener {

    public enum Phase { IDLE, PREP, RUNNING, SHOWDOWN, ENDED }

    private final SupplyManager supplyManager;
    private final JavaPlugin plugin;

    private static final int START_LIVES = 2;

    private final Map<UUID, Integer> lives = new ConcurrentHashMap<>();
    private final Map<UUID, Location> pendingRespawn = new ConcurrentHashMap<>();
    private final Set<UUID> alive = ConcurrentHashMap.newKeySet();

    private BossBar bossBar;
    private Scoreboard scoreboard;
    private Objective livesObj;

    private Phase phase = Phase.IDLE;

    private BukkitTask ticker;
    private BukkitTask borderTask;
    private BukkitTask showdownGate;

    private int phaseRemainingSec = 0;
    private int shrinkIndex = 0;
    private boolean showdownEnabled = false;

    // ===== 모드 설정 =====
    private boolean miniMode = false;

    private int prepSec;
    private int shrinkIntervalSec;
    private int showdownAfterSec;
    private double[] currentBorderSizes;
    private int prepEffectTicks;

    private Location borderCenter;
    private Location showdownLocA;
    private Location showdownLocB;

    private double randomSpawnMinX;
    private double randomSpawnMaxX;
    private double randomSpawnMinZ;
    private double randomSpawnMaxZ;

    // ===== 일반 모드 기본값 =====
    private static final int NORMAL_PREP_SEC = 240;
    private static final int NORMAL_SHRINK_INTERVAL_SEC = 180;
    private static final int NORMAL_SHOWDOWN_AFTER_SEC = 8 * 60;
    private static final double[] NORMAL_BORDER_SIZES = {600, 420, 300, 200, 140, 90, 60, 40};

    // ===== 미니 모드 기본값 =====
    private static final int MINI_PREP_SEC = 90;
    private static final int MINI_SHRINK_INTERVAL_SEC = 90;
    private static final int MINI_SHOWDOWN_AFTER_SEC = 180;
    private static final double[] MINI_BORDER_SIZES = {150, 100, 70, 40, 25};

    // 쇼다운 승부예측
    private static final String SHOWDOWN_VOTE_TITLE = "§6쇼다운 승부예측";
    private final Map<UUID, UUID> showdownVotes = new ConcurrentHashMap<>();
    private UUID showdownPlayerAId;
    private UUID showdownPlayerBId;
    private boolean showdownVotingOpen = false;

    // 중도참가
    private static final String LATE_JOIN_TITLE = "§b중도 참가 선택";
    private final Set<UUID> pendingLateJoinChoice = ConcurrentHashMap.newKeySet();

    public GameManager(SupplyManager supplyManager, JavaPlugin plugin) {
        this.supplyManager = supplyManager;
        this.plugin = plugin;

        applyNormalModeConfig();
    }

    public Phase getPhase() {
        return phase;
    }

    public boolean isRunning() {
        return phase == Phase.PREP || phase == Phase.RUNNING || phase == Phase.SHOWDOWN;
    }

    public boolean isAlive(Player p) {
        return alive.contains(p.getUniqueId());
    }

    public boolean isMiniMode() {
        return miniMode;
    }

    private void applyNormalModeConfig() {
        World w = Bukkit.getWorlds().get(0);

        miniMode = false;
        prepSec = NORMAL_PREP_SEC;
        shrinkIntervalSec = NORMAL_SHRINK_INTERVAL_SEC;
        showdownAfterSec = NORMAL_SHOWDOWN_AFTER_SEC;
        currentBorderSizes = Arrays.copyOf(NORMAL_BORDER_SIZES, NORMAL_BORDER_SIZES.length);
        prepEffectTicks = (prepSec + 5) * 20;

        borderCenter = new Location(w, -183.0, w.getHighestBlockYAt(100, 100), -93.0);
        showdownLocA = new Location(w, -183.0, 64, -118.5, 180f, 0f);
        showdownLocB = new Location(w, -183.0, 64, -68.5, 0f, 0f);

        randomSpawnMinX = -140.0;
        randomSpawnMaxX = 70.0;
        randomSpawnMinZ = -200.0;
        randomSpawnMaxZ = 45.0;
    }

    private void applyMiniModeConfig() {
        World w = Bukkit.getWorlds().get(0);

        miniMode = true;
        prepSec = MINI_PREP_SEC;
        shrinkIntervalSec = MINI_SHRINK_INTERVAL_SEC;
        showdownAfterSec = MINI_SHOWDOWN_AFTER_SEC;
        currentBorderSizes = Arrays.copyOf(MINI_BORDER_SIZES, MINI_BORDER_SIZES.length);
        prepEffectTicks = (prepSec + 5) * 20;

        // 유저가 준 미니맵 정보
        borderCenter = new Location(w, -34.0, w.getHighestBlockYAt(-34, 25), 25.0);

        // 필요하면 여기 쇼다운 좌표는 나중에 더 다듬어도 됨
        showdownLocA = new Location(w, -34.0, 4, 15.5, 180f, 0f);
        showdownLocB = new Location(w, -34.0, 4, 35.5, 0f, 0f);

        randomSpawnMinX = -95.0;
        randomSpawnMaxX = 23.0;
        randomSpawnMinZ = -30.0;
        randomSpawnMaxZ = 85.0;
    }

    public void startGame() {
        applyNormalModeConfig();
        startConfiguredGame();
    }

    public void startMiniGame() {
        applyMiniModeConfig();
        startConfiguredGame();
    }

    private void startConfiguredGame() {
        if (phase != Phase.IDLE && phase != Phase.ENDED) return;

        phase = Phase.PREP;
        shrinkIndex = 0;
        showdownEnabled = false;

        alive.clear();
        lives.clear();
        pendingRespawn.clear();
        pendingLateJoinChoice.clear();
        resetShowdownVoting();

        initBossBar();

        if (supplyManager != null) supplyManager.start();

        for (Player p : Bukkit.getOnlinePlayers()) {
            setAlivePlayer(p);
            lives.put(p.getUniqueId(), START_LIVES);
        }

        initScoreboard();
        Bukkit.broadcastMessage("§a[게임] 모든 플레이어 목숨: §e" + START_LIVES);

        setupInitialBorder();
        applyPrepBuffs();

        phaseRemainingSec = prepSec;
        startTicker();

        if (miniMode) {
            Bukkit.broadcastMessage("§d[미니 도능] §f준비 시작! §e" + prepSec + "초§f 후 게임이 시작됩니다.");
        } else {
            Bukkit.broadcastMessage("§a[게임] 준비 시작! §f" + (prepSec / 60) + "분 후 게임이 시작됩니다.");
        }
    }

    private void applyPrepBuffs() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isAlive(p)) continue;

            p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, prepEffectTicks, 4, true, false, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, prepEffectTicks, 0, true, false, true));
            p.setFoodLevel(20);
            p.setSaturation(20);
        }
    }

    private void clearPrepBuffs() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.removePotionEffect(PotionEffectType.RESISTANCE);
            p.removePotionEffect(PotionEffectType.SATURATION);
        }
    }

    private void initBossBar() {
        if (bossBar != null) {
            bossBar.removeAll();
        }

        bossBar = Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SOLID);
        bossBar.setVisible(true);

        for (Player p : Bukkit.getOnlinePlayers()) {
            bossBar.addPlayer(p);
        }
        updateBossBar();
    }

    private void updateBossBar() {
        if (bossBar == null) return;

        String title;
        double progress = 1.0;

        if (phase == Phase.PREP) {
            title = (miniMode ? "§d미니 도능 준비중" : "§a준비중") + " §f| 시작까지 §e" + phaseRemainingSec + "초";
            progress = Math.max(0.0, Math.min(1.0, phaseRemainingSec / (double) prepSec));
        } else if (phase == Phase.RUNNING) {
            title = (miniMode ? "§d미니 도능 진행중" : "§c진행중") + " §f| 다음 축소까지 §e" + phaseRemainingSec + "초";
            progress = Math.max(0.0, Math.min(1.0, phaseRemainingSec / (double) shrinkIntervalSec));
        } else if (phase == Phase.SHOWDOWN) {
            title = "§6결투 §f| 1 vs 1";
            progress = 1.0;
        } else {
            title = "§7대기중";
            progress = 1.0;
        }

        bossBar.setTitle(title);
        bossBar.setProgress(progress);
    }

    private void setAlivePlayer(Player p) {
        alive.add(p.getUniqueId());
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(p.getAttribute(Attribute.MAX_HEALTH).getValue());
        p.setFoodLevel(20);
        p.setSaturation(20);
    }

    private Location pickRandomRespawn(World w) {
        WorldBorder border = w.getWorldBorder();

        for (int i = 0; i < 120; i++) {
            double x = ThreadLocalRandom.current().nextDouble(randomSpawnMinX, randomSpawnMaxX);
            double z = ThreadLocalRandom.current().nextDouble(randomSpawnMinZ, randomSpawnMaxZ);

            Location test = new Location(w, x, w.getSpawnLocation().getY(), z);
            if (!border.isInside(test)) continue;

            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);

            int y = w.getHighestBlockYAt(bx, bz);
            Location loc = new Location(w, bx + 0.5, y + 1.0, bz + 0.5);

            Material below = w.getBlockAt(bx, y, bz).getType();
            if (below == Material.LAVA || below == Material.MAGMA_BLOCK || below == Material.CACTUS) continue;

            if (!w.getBlockAt(bx, y + 1, bz).isPassable()) continue;
            if (!w.getBlockAt(bx, y + 2, bz).isPassable()) continue;
            if (!border.isInside(loc)) continue;

            return loc;
        }

        Location fallback = borderCenter.clone();
        int y = w.getHighestBlockYAt((int) Math.floor(fallback.getX()), (int) Math.floor(fallback.getZ()));
        return new Location(w, fallback.getX() + 0.5, y + 1.0, fallback.getZ() + 0.5);
    }

    private void setupInitialBorder() {
        World w = Bukkit.getWorlds().get(0);
        WorldBorder border = w.getWorldBorder();

        Location center = (borderCenter != null) ? borderCenter : w.getSpawnLocation();
        border.setCenter(center);

        border.setDamageAmount(0.5);
        border.setDamageBuffer(0.0);
        border.setWarningDistance(8);
        border.setWarningTime(10);

        border.setSize(currentBorderSizes[0]);
    }

    private void shrinkBorderOneStep() {
        World w = Bukkit.getWorlds().get(0);
        WorldBorder border = w.getWorldBorder();

        int next = Math.min(shrinkIndex + 1, currentBorderSizes.length - 1);
        if (next == shrinkIndex) return;

        double newSize = currentBorderSizes[next];
        shrinkIndex = next;

        border.setSize(newSize, TimeUnit.SECONDS, 10L);
        Bukkit.broadcastMessage("§c[자기장] §f자기장이 축소됩니다! §7(새 크기: " + (int) newSize + ")");
        w.playSound(border.getCenter(), Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 0.8f);
    }

    private void initScoreboard() {
        ScoreboardManager sm = Bukkit.getScoreboardManager();
        if (sm == null) return;

        scoreboard = sm.getNewScoreboard();
        livesObj = scoreboard.registerNewObjective("lives", "dummy", "§c남은 목숨");
        livesObj.setDisplaySlot(DisplaySlot.SIDEBAR);

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(scoreboard);
        }

        updateScoreboardAll();
    }

    private void updateScoreboardAll() {
        if (scoreboard == null || livesObj == null) return;

        for (UUID id : lives.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;

            int l = lives.getOrDefault(id, 0);
            livesObj.getScore("§f" + p.getName()).setScore(l);
        }
    }

    private void startTicker() {
        stopAllTasks();

        ticker = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!isRunning()) return;

            updateBossBar();

            if (phase == Phase.PREP || phase == Phase.RUNNING) {
                phaseRemainingSec--;
                if (phaseRemainingSec <= 0) {
                    if (phase == Phase.PREP) {
                        clearPrepBuffs();
                        startRunningPhase();
                    } else if (phase == Phase.RUNNING) {
                        shrinkBorderOneStep();
                        phaseRemainingSec = shrinkIntervalSec;
                    }
                }
            }

            if (phase == Phase.RUNNING && showdownEnabled) {
                if (getAlivePlayers().size() <= 2) {
                    startShowdown();
                }
            }
        }, 0L, 20L);
    }

    private void startRunningPhase() {
        phase = Phase.RUNNING;
        phaseRemainingSec = shrinkIntervalSec;

        showdownEnabled = false;
        if (showdownGate != null) showdownGate.cancel();

        showdownGate = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            showdownEnabled = true;
            if (phase == Phase.RUNNING) {
                Bukkit.broadcastMessage("§6[쇼다운] §f이제부터 2명 이하가 되면 결투장으로 이동합니다!");
                if (getAlivePlayers().size() <= 2) startShowdown();
            }
        }, showdownAfterSec * 20L);

        if (miniMode) {
            Bukkit.broadcastMessage("§d[미니 도능] §f시작!");
        } else {
            Bukkit.broadcastMessage("§c[게임] §f시작!");
        }

        World w = Bukkit.getWorlds().get(0);
        w.playSound(w.getSpawnLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 1.5f);
    }

    private void startShowdown() {
        if (phase != Phase.RUNNING) return;

        List<Player> left = getAlivePlayers();
        if (left.size() == 1) {
            announceWinner(left.get(0));
            return;
        }
        if (left.size() <= 0) {
            endGameNoWinner();
            return;
        }
        if (left.size() < 2) return;

        phase = Phase.SHOWDOWN;

        World w = Bukkit.getWorlds().get(0);
        WorldBorder border = w.getWorldBorder();
        double finalSize = currentBorderSizes[currentBorderSizes.length - 1];
        shrinkIndex = currentBorderSizes.length - 1;

        border.setSize(finalSize, TimeUnit.SECONDS, 10L);
        Bukkit.broadcastMessage("§c[자기장] §f쇼다운이 시작되어 자기장이 최종 단계까지 축소됩니다! §7(크기: " + (int) finalSize + ")");

        Player a = left.get(0);
        Player b = left.get(1);

        showdownPlayerAId = a.getUniqueId();
        showdownPlayerBId = b.getUniqueId();
        showdownVotes.clear();
        showdownVotingOpen = true;

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!alive.contains(p.getUniqueId())) {
                p.setGameMode(GameMode.SPECTATOR);
            }
        }

        a.teleport(showdownLocA);
        b.teleport(showdownLocB);

        Bukkit.broadcastMessage("§6[결투] §f남은 두 명이 결투장으로 이동합니다!");
        Bukkit.broadcastMessage("§6[승부예측] §f관전자들은 인벤토리 UI에서 누가 이길지 투표하세요!");

        openShowdownVoteUIForAllSpectators();
        broadcastVoteStatus();

        World showw = showdownLocA.getWorld();
        if (showw != null) {
            showw.playSound(showdownLocA, Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.2f);
        }
    }

    private boolean isSpectatorVoter(Player p) {
        return p != null
                && p.isOnline()
                && !alive.contains(p.getUniqueId())
                && p.getGameMode() == GameMode.SPECTATOR;
    }

    private int getVoteCount(UUID targetId) {
        int count = 0;
        for (UUID voted : showdownVotes.values()) {
            if (Objects.equals(voted, targetId)) count++;
        }
        return count;
    }

    private int getTotalVotes() {
        return showdownVotes.size();
    }

    private ItemStack createVoteHead(Player target) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(target);
            meta.setDisplayName("§e" + target.getName());

            int votes = getVoteCount(target.getUniqueId());
            List<String> lore = new ArrayList<>();
            lore.add("§7이 플레이어의 승리를 예측합니다.");
            lore.add("§7현재 득표수: §a" + votes + "표");
            lore.add(" ");
            lore.add("§f클릭하여 투표");
            meta.setLore(lore);

            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            head.setItemMeta(meta);
        }
        return head;
    }

    private void openShowdownVoteUI(Player spectator) {
        if (!showdownVotingOpen) {
            spectator.sendMessage("§c[승부예측] 현재 투표가 열려있지 않습니다.");
            return;
        }

        Player a = showdownPlayerAId == null ? null : Bukkit.getPlayer(showdownPlayerAId);
        Player b = showdownPlayerBId == null ? null : Bukkit.getPlayer(showdownPlayerBId);

        if (a == null || b == null) {
            spectator.sendMessage("§c[승부예측] 쇼다운 플레이어 정보를 찾을 수 없습니다.");
            return;
        }

        Inventory inv = Bukkit.createInventory(null, 27, SHOWDOWN_VOTE_TITLE);
        inv.setItem(11, createVoteHead(a));
        inv.setItem(15, createVoteHead(b));

        spectator.openInventory(inv);
    }

    private void openShowdownVoteUIForAllSpectators() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isSpectatorVoter(p)) {
                openShowdownVoteUI(p);
            }
        }
    }

    private void broadcastVoteStatus() {
        Player a = showdownPlayerAId == null ? null : Bukkit.getPlayer(showdownPlayerAId);
        Player b = showdownPlayerBId == null ? null : Bukkit.getPlayer(showdownPlayerBId);

        if (a == null || b == null) return;

        int aVotes = getVoteCount(a.getUniqueId());
        int bVotes = getVoteCount(b.getUniqueId());
        int total = aVotes + bVotes;

        if (total <= 0) {
            Bukkit.broadcastMessage("§6[승부예측] §f아직 투표가 없습니다.");
            return;
        }

        double aPct = (aVotes * 100.0) / total;
        double bPct = (bVotes * 100.0) / total;

        Bukkit.broadcastMessage(
                "§6[승부예측] §e" + a.getName() + "§f: §a" + aVotes + "표 §7(" + String.format("%.1f", aPct) + "%) "
                        + "§f| §e" + b.getName() + "§f: §a" + bVotes + "표 §7(" + String.format("%.1f", bPct) + "%)"
        );
    }

    private void closeShowdownVoting() {
        showdownVotingOpen = false;
    }

    private void resetShowdownVoting() {
        showdownVotes.clear();
        showdownPlayerAId = null;
        showdownPlayerBId = null;
        showdownVotingOpen = false;
    }

    private void announcePredictionResult(Player winner) {
        if (winner == null) return;

        int winnerVotes = getVoteCount(winner.getUniqueId());
        int totalVotes = getTotalVotes();

        Bukkit.broadcastMessage("§6[승부예측 결과] §e" + winner.getName() + "§f에게 §a" + winnerVotes + "표§f가 몰렸습니다. §7(총 투표: " + totalVotes + "표)");

        if (totalVotes > 0) {
            List<String> correctSpectators = new ArrayList<>();
            for (Map.Entry<UUID, UUID> entry : showdownVotes.entrySet()) {
                if (Objects.equals(entry.getValue(), winner.getUniqueId())) {
                    Player p = Bukkit.getPlayer(entry.getKey());
                    if (p != null) correctSpectators.add(p.getName());
                }
            }

            if (correctSpectators.isEmpty()) {
                Bukkit.broadcastMessage("§7[승부예측] 정답을 맞춘 관전자가 없습니다.");
            } else {
                Bukkit.broadcastMessage("§a[승부예측] 적중: §f" + String.join(", ", correctSpectators));
            }
        }
    }

    private boolean isGameInProgressForLateJoin() {
        return phase == Phase.PREP || phase == Phase.RUNNING;
    }

    private ItemStack createMenuItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void openLateJoinUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, LATE_JOIN_TITLE);

        inv.setItem(11, createMenuItem(
                Material.GREEN_WOOL,
                "§a목숨 1개로 참가하기",
                List.of(
                        "§7지금 게임에 참가합니다.",
                        "§7시작 장비: §f가죽갑옷 풀셋, 나무검",
                        "§7남은 목숨: §c1"
                )
        ));

        inv.setItem(15, createMenuItem(
                Material.RED_WOOL,
                "§c관전하기",
                List.of(
                        "§7이번 판은 관전자로 참여합니다."
                )
        ));

        player.openInventory(inv);
    }

    private void setSpectator(Player player) {
        alive.remove(player.getUniqueId());
        pendingRespawn.remove(player.getUniqueId());
        lives.remove(player.getUniqueId());

        player.setGameMode(GameMode.SPECTATOR);
        player.getInventory().clear();
        player.getEquipment().clear();

        Location specLoc = Bukkit.getWorlds().get(0).getSpawnLocation().clone().add(0.5, 1, 0.5);
        player.teleport(specLoc);

        if (scoreboard != null) {
            player.setScoreboard(scoreboard);
        }
    }

    private void giveLateJoinKit(Player player) {
        PlayerInventory inv = player.getInventory();
        inv.clear();

        inv.addItem(new ItemStack(Material.WOODEN_SWORD));

        inv.setHelmet(new ItemStack(Material.LEATHER_HELMET));
        inv.setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
        inv.setLeggings(new ItemStack(Material.LEATHER_LEGGINGS));
        inv.setBoots(new ItemStack(Material.LEATHER_BOOTS));
    }

    private void joinMidGame(Player player) {
        if (!isGameInProgressForLateJoin()) {
            player.sendMessage("§c[게임] 지금은 중도 참가가 불가능합니다.");
            return;
        }

        if (phase == Phase.SHOWDOWN) {
            player.sendMessage("§c[게임] 쇼다운 중에는 참가할 수 없습니다.");
            setSpectator(player);
            return;
        }

        UUID id = player.getUniqueId();

        alive.add(id);
        lives.put(id, 1);
        pendingRespawn.remove(id);

        player.setGameMode(GameMode.SURVIVAL);
        giveLateJoinKit(player);

        Location spawn = pickRandomRespawn(player.getWorld());
        player.teleport(spawn);

        double maxHealth = 20.0;
        if (player.getAttribute(Attribute.MAX_HEALTH) != null) {
            maxHealth = player.getAttribute(Attribute.MAX_HEALTH).getValue();
        }
        player.setHealth(Math.min(maxHealth, player.getHealth()));
        player.setFoodLevel(20);
        player.setSaturation(20);

        if (phase == Phase.PREP) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, prepEffectTicks, 4, true, false, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, prepEffectTicks, 0, true, false, true));
        }

        if (bossBar != null) {
            bossBar.addPlayer(player);
        }

        if (scoreboard != null) {
            player.setScoreboard(scoreboard);
        }
        updateScoreboardAll();

        Bukkit.broadcastMessage("§a[중도참가] §f" + player.getName() + "§7 님이 §c목숨 1개§7로 참가했습니다.");
        player.sendMessage("§a[게임] 목숨 1개로 게임에 참가했습니다!");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();

        if (bossBar != null) {
            bossBar.addPlayer(player);
        }

        if (phase == Phase.IDLE || phase == Phase.ENDED) {
            if (scoreboard != null) player.setScoreboard(scoreboard);
            return;
        }

        if (phase == Phase.SHOWDOWN) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                setSpectator(player);
                player.sendMessage("§6[게임] 현재 쇼다운 중이므로 관전으로 참가합니다.");

                if (showdownVotingOpen) {
                    openShowdownVoteUI(player);
                }
            }, 20L);
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;

            pendingLateJoinChoice.add(player.getUniqueId());
            player.setGameMode(GameMode.ADVENTURE);
            player.getInventory().clear();
            player.getEquipment().clear();

            Location lobby = Bukkit.getWorlds().get(0).getSpawnLocation().clone().add(0.5, 1, 0.5);
            player.teleport(lobby);

            if (scoreboard != null) {
                player.setScoreboard(scoreboard);
            }

            player.sendMessage("§b[게임] 현재 진행 중인 게임입니다.");
            player.sendMessage("§f관전할지, 목숨 1개로 참가할지 선택해주세요.");
            openLateJoinUI(player);
        }, 20L);
    }

    @EventHandler
    public void onLateJoinChoiceClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getView().getTitle() == null || !e.getView().getTitle().equals(LATE_JOIN_TITLE)) return;

        e.setCancelled(true);

        UUID id = player.getUniqueId();
        if (!pendingLateJoinChoice.contains(id)) {
            player.closeInventory();
            return;
        }

        int slot = e.getRawSlot();

        if (slot == 11) {
            pendingLateJoinChoice.remove(id);
            player.closeInventory();
            joinMidGame(player);
        } else if (slot == 15) {
            pendingLateJoinChoice.remove(id);
            player.closeInventory();
            setSpectator(player);
            player.sendMessage("§7[게임] 관전자로 참여합니다.");
        }
    }

    @EventHandler
    public void onLateJoinChoiceClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player player)) return;
        if (e.getView().getTitle() == null || !e.getView().getTitle().equals(LATE_JOIN_TITLE)) return;

        UUID id = player.getUniqueId();
        if (!pendingLateJoinChoice.contains(id)) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (!pendingLateJoinChoice.contains(id)) return;

            pendingLateJoinChoice.remove(id);
            setSpectator(player);
            player.sendMessage("§7[게임] 선택하지 않아 관전자로 전환되었습니다.");
        }, 1L);
    }

    @EventHandler
    public void onShowdownVoteClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getView().getTitle() == null || !e.getView().getTitle().equals(SHOWDOWN_VOTE_TITLE)) return;

        e.setCancelled(true);

        if (!showdownVotingOpen) {
            player.closeInventory();
            player.sendMessage("§c[승부예측] 투표가 종료되었습니다.");
            return;
        }

        if (!isSpectatorVoter(player)) {
            player.sendMessage("§c[승부예측] 탈락한 관전자만 투표할 수 있습니다.");
            return;
        }

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) return;

        Player a = showdownPlayerAId == null ? null : Bukkit.getPlayer(showdownPlayerAId);
        Player b = showdownPlayerBId == null ? null : Bukkit.getPlayer(showdownPlayerBId);
        if (a == null || b == null) {
            player.sendMessage("§c[승부예측] 투표 대상을 찾을 수 없습니다.");
            return;
        }

        UUID votedTarget = null;
        int slot = e.getRawSlot();

        if (slot == 11) {
            votedTarget = a.getUniqueId();
        } else if (slot == 15) {
            votedTarget = b.getUniqueId();
        } else {
            return;
        }

        UUID oldVote = showdownVotes.put(player.getUniqueId(), votedTarget);
        Player votedPlayer = Bukkit.getPlayer(votedTarget);

        if (votedPlayer != null) {
            if (Objects.equals(oldVote, votedTarget)) {
                player.sendMessage("§e[승부예측] 이미 §f" + votedPlayer.getName() + "§e에게 투표했습니다.");
            } else {
                player.sendMessage("§a[승부예측] §f" + votedPlayer.getName() + "§a의 승리에 투표했습니다.");
                Bukkit.broadcastMessage("§6[승부예측] §f" + player.getName() + "님이 투표했습니다.");
                broadcastVoteStatus();
            }
        }

        player.closeInventory();
    }

    private void checkWinner() {
        if (phase == Phase.IDLE || phase == Phase.ENDED) return;

        List<Player> left = getAlivePlayers();
        if (left.size() == 1) {
            announceWinner(left.get(0));
        } else if (left.size() == 0) {
            endGameNoWinner();
        }
    }

    private void announceWinner(Player winner) {
        phase = Phase.ENDED;
        closeShowdownVoting();
        stopAllTasks();

        Bukkit.broadcastMessage("§b[우승] §e" + winner.getName() + "§f 님이 우승했습니다!");
        announcePredictionResult(winner);

        World w = winner.getWorld();
        w.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);

        resetShowdownVoting();
        pendingLateJoinChoice.clear();
    }

    private void endGameNoWinner() {
        phase = Phase.ENDED;
        closeShowdownVoting();
        stopAllTasks();
        Bukkit.broadcastMessage("§7[게임] 우승자 없이 종료되었습니다.");
        resetShowdownVoting();
        pendingLateJoinChoice.clear();
    }

    private void stopAllTasks() {
        if (ticker != null) { ticker.cancel(); ticker = null; }
        if (borderTask != null) { borderTask.cancel(); borderTask = null; }
        if (showdownGate != null) { showdownGate.cancel(); showdownGate = null; }
    }

    private List<Player> getAlivePlayers() {
        List<Player> list = new ArrayList<>();
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                list.add(p);
            }
        }
        return list;
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        if (isRunning() && !alive.contains(id)) {
            e.setRespawnLocation(p.getWorld().getSpawnLocation().clone().add(0.5, 1, 0.5));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) p.setGameMode(GameMode.SPECTATOR);
            }, 1L);
            return;
        }

        Location resp = pendingRespawn.remove(id);
        if (resp != null) {
            e.setRespawnLocation(resp);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        UUID id = dead.getUniqueId();

        if (!isRunning()) return;
        if (!alive.contains(id)) return;

        int left = lives.getOrDefault(id, START_LIVES) - 1;
        lives.put(id, left);

        if (left > 0) {
            Bukkit.broadcastMessage("§e[사망] §f" + dead.getName() + "§7 사망! 남은 목숨: §c" + left);

            World w = dead.getWorld();
            Location resp = pickRandomRespawn(w);
            pendingRespawn.put(id, resp);

        } else {
            Bukkit.broadcastMessage("§c[탈락] §f" + dead.getName() + "§7 님이 탈락했습니다!");
            alive.remove(id);
            pendingRespawn.remove(id);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (dead.isOnline()) dead.setGameMode(GameMode.SPECTATOR);
            }, 1L);
        }

        updateScoreboardAll();

        if (phase == Phase.RUNNING && showdownEnabled && getAlivePlayers().size() <= 2) {
            Bukkit.getScheduler().runTask(plugin, this::startShowdown);
        }

        Bukkit.getScheduler().runTask(plugin, this::checkWinner);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        alive.remove(id);
        pendingLateJoinChoice.remove(id);

        if (isRunning()) Bukkit.getScheduler().runTask(plugin, this::checkWinner);
    }
}