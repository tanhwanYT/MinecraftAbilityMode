package my.pkg;

import my.pkg.abilities.Ability;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.concurrent.TimeUnit;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scoreboard.*;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GameManager implements Listener {

    public enum Phase { IDLE, PREP, RUNNING, SHOWDOWN, ENDED }

    private final SupplyManager supplyManager;
    // ===== 목숨 시스템 =====
    private static final int START_LIVES = 2;
    private final Map<UUID, Integer> lives = new ConcurrentHashMap<>();

    // 죽고 리스폰할 위치(첫 죽음일 때 랜덤 리스폰용)
    private final Map<UUID, Location> pendingRespawn = new ConcurrentHashMap<>();

    private  BossBar bossBar;
    // 보더 중심
    private Location borderCenter;

    // ===== 스코어보드 =====
    private Scoreboard scoreboard;
    private Objective livesObj;

    private final JavaPlugin plugin;

    private Phase phase = Phase.IDLE;

    private BukkitTask ticker;          // 1초마다 안내/타이머
    private BukkitTask borderTask;      // 다음 축소 예약(선택)
    private BukkitTask showdownGate;    // 12분 체크

    private int phaseRemainingSec = 0;  // 현재 페이즈 남은 초
    private int shrinkIndex = 0;

    // ✅ 살아있는 플레이어 추적 (관전자는 제외)
    private final Set<UUID> alive = ConcurrentHashMap.newKeySet();

    // ====== 설정값 ======
    private static final int PREP_SEC = 1 * 240;            // 준비 4분
    private static final int SHRINK_INTERVAL_SEC = 1 * 180; // 축소 주기 4분
    private static final int SHOWDOWN_AFTER_SEC = 3 * 60; // RUNNING 시작 후 12분

    // 보더 단계(원하는대로 수정)
    private final double[] borderSizes = {600, 420, 300, 200, 140, 90, 60, 40};

    // 쇼다운 장소(원하는 좌표로 바꿔)
    private final Location showdownLocA;
    private final Location showdownLocB;
    private static final int PREP_EFFECT_TICKS = (1 * 240 + 5) * 20; // 4분 + 여유 5초

    private void applyPrepBuffs() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isAlive(p)) continue;

            // 저항 II (원하면 0=I, 1=II)
            p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, PREP_EFFECT_TICKS, 4, true, false, true));

            // 포화 (앰프 0이면 충분)
            p.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, PREP_EFFECT_TICKS, 0, true, false, true));

            // 혹시 몰라서 배고픔 꽉
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

    public GameManager(SupplyManager supplyManager, JavaPlugin plugin) {
        this.supplyManager = supplyManager;
        this.plugin = plugin;

        World w = Bukkit.getWorlds().get(0); // 기본 월드
        // 예시: 스폰 근처
        this.borderCenter = new Location(w, -183.0, w.getHighestBlockYAt(100, 100), -93.0);
        this.showdownLocA = new Location(w, -183.0, 64, -118.5, 180f, 0f);
        this.showdownLocB = new Location(w, -183.0, 64, -68.5, 0f, 0f);
    }

    public Phase getPhase() { return phase; }

    public boolean isRunning() {
        return phase == Phase.PREP || phase == Phase.RUNNING || phase == Phase.SHOWDOWN;
    }

    public boolean isAlive(Player p) {
        return alive.contains(p.getUniqueId());
    }

    // ===== 시작 =====
    public void startGame() {
        if (phase != Phase.IDLE && phase != Phase.ENDED) return;

        phase = Phase.PREP;
        shrinkIndex = 0;
        alive.clear();
        lives.clear();
        pendingRespawn.clear();
        initBossBar();

        if (supplyManager != null) supplyManager.start();
        // 온라인 플레이어 세팅
        for (Player p : Bukkit.getOnlinePlayers()) {
            setAlivePlayer(p);
            lives.put(p.getUniqueId(), START_LIVES);
        }

        initScoreboard();
        Bukkit.broadcastMessage("§a[게임] 모든 플레이어 목숨: §e" + START_LIVES);


        // 보더 초기화
        setupInitialBorder();

        // 재생 포화
        applyPrepBuffs();

        // 준비시간 시작
        phaseRemainingSec = PREP_SEC;
        startTicker();

        Bukkit.broadcastMessage("§a[게임] 준비 시작! §f4분 후 게임이 시작됩니다.");
    }

    private void initBossBar() {
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
            title = "§a준비중 §f| 시작까지 §e" + phaseRemainingSec + "초";
            progress = Math.max(0.0, Math.min(1.0, phaseRemainingSec / (double) PREP_SEC));
        } else if (phase == Phase.RUNNING) {
            title = "§c진행중 §f| 다음 축소까지 §e" + phaseRemainingSec + "초";
            progress = Math.max(0.0, Math.min(1.0, phaseRemainingSec / (double) SHRINK_INTERVAL_SEC));
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
        Location c = border.getCenter();

        double half = border.getSize() / 2.0;
        double margin = 12.0; // ✅ 여유 크게 (8~16 추천)
        double usable = Math.max(10.0, half - margin);

        for (int i = 0; i < 80; i++) { // ✅ 시도 횟수 증가
            double x = c.getX() + ThreadLocalRandom.current().nextDouble(-usable, usable);
            double z = c.getZ() + ThreadLocalRandom.current().nextDouble(-usable, usable);

            // ✅ “진짜로” 보더 안쪽인지 체크 (확정)
            if (!border.isInside(new Location(w, x, c.getY(), z))) continue;

            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);

            int y = w.getHighestBlockYAt(bx, bz);
            Location loc = new Location(w, bx + 0.5, y + 1.0, bz + 0.5);

            Material below = w.getBlockAt(bx, y, bz).getType();
            if (below == Material.LAVA || below == Material.MAGMA_BLOCK || below == Material.CACTUS) continue;

            if (!w.getBlockAt(bx, y + 1, bz).isPassable()) continue;
            if (!w.getBlockAt(bx, y + 2, bz).isPassable()) continue;

            // ✅ 최종 위치도 보더 안인지 다시 체크
            if (!border.isInside(loc)) continue;

            return loc;
        }

        return w.getSpawnLocation().clone().add(0.5, 1, 0.5);
    }

    // ===== 보더 =====
    private void setupInitialBorder() {
        World w = Bukkit.getWorlds().get(0);
        WorldBorder border = w.getWorldBorder();

        Location center = (borderCenter != null) ? borderCenter : w.getSpawnLocation();
        border.setCenter(center);

        border.setDamageAmount(0.5);
        border.setDamageBuffer(0.0);
        border.setWarningDistance(8);
        border.setWarningTime(10);

        border.setSize(borderSizes[0]);
    }

    private void shrinkBorderOneStep() {
        World w = Bukkit.getWorlds().get(0);
        WorldBorder border = w.getWorldBorder();

        int next = Math.min(shrinkIndex + 1, borderSizes.length - 1);
        if (next == shrinkIndex) return;

        double newSize = borderSizes[next];
        shrinkIndex = next;

        border.setSize(newSize, TimeUnit.SECONDS, 10L);// 10초 동안 부드럽게 줄이기(원하면 60초로)
        Bukkit.broadcastMessage("§c[자기장] §f자기장이 축소됩니다! §7(새 크기: " + (int)newSize + ")");
        w.playSound(border.getCenter(), Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 0.8f);
    }

    private void initScoreboard() {
        ScoreboardManager sm = Bukkit.getScoreboardManager();
        if (sm == null) return;

        scoreboard = sm.getNewScoreboard();
        livesObj = scoreboard.registerNewObjective("lives", "dummy", "§c남은 목숨");
        livesObj.setDisplaySlot(DisplaySlot.SIDEBAR);

        // 모든 온라인 플레이어에게 적용
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(scoreboard);
        }

        updateScoreboardAll();
    }

    private void updateScoreboardAll() {
        if (scoreboard == null || livesObj == null) return;

        // 점수 갱신
        for (UUID id : lives.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;

            int l = lives.getOrDefault(id, 0);
            livesObj.getScore("§f" + p.getName()).setScore(l);
        }

        // 탈락/퇴장 등으로 목록에서 빠진 사람의 old entry 정리(선택)
        // 너무 깔끔하게 하고 싶으면 엔트리 추적용 Set<String> 따로 관리하면 됨.
    }

    // ===== 타이머/페이즈 진행 =====
    private void startTicker() {
        stopAllTasks();

        ticker = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // 매초 실행
            if (!isRunning()) return;

            updateBossBar();

            // 감소
            if (phase == Phase.PREP || phase == Phase.RUNNING) {
                phaseRemainingSec--;
                if (phaseRemainingSec <= 0) {
                    if (phase == Phase.PREP) {
                        clearPrepBuffs();
                        startRunningPhase();
                    } else if (phase == Phase.RUNNING) {
                        // 축소 타이밍
                        shrinkBorderOneStep();
                        phaseRemainingSec = SHRINK_INTERVAL_SEC;
                    }
                }
            }

            // 쇼다운 조건: RUNNING 시작 후 12분이 지난 뒤엔 alive<=2 되면 바로 쇼다운
            if (phase == Phase.RUNNING && shouldEnterShowdown()) {
                if (getAlivePlayers().size() <= 2) {
                    startShowdown();
                }
            }
        }, 0L, 20L);

        // RUNNING 시작 후 12분 경과 체크용 플래그
        showdownGate = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // 12분 뒤 메시지
            if (phase == Phase.RUNNING) {
                Bukkit.broadcastMessage("§6[쇼다운] §f이제부터 2명 이하가 되면 결투장으로 이동합니다!");
            }
        }, SHOWDOWN_AFTER_SEC * 20L);
    }

    // “12분 지났는지” 판정: showdownGate가 실행된 이후를 간단히 체크하고 싶으면 boolean 플래그로도 가능
    private boolean showdownEnabled = false;
    private boolean shouldEnterShowdown() {
        // 가장 간단: 12분 후 task에서 플래그 세팅
        // (위 showdownGate를 아래처럼 바꾸면 됨)
        return showdownEnabled;
    }

    private void startRunningPhase() {
        phase = Phase.RUNNING;
        phaseRemainingSec = SHRINK_INTERVAL_SEC;

        // 12분 후 쇼다운 활성화 플래그
        showdownEnabled = false;
        if (showdownGate != null) showdownGate.cancel();
        showdownGate = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            showdownEnabled = true;
            if (phase == Phase.RUNNING) {
                Bukkit.broadcastMessage("§6[쇼다운] §f이제부터 2명 이하가 되면 결투장으로 이동합니다!");
                // 이미 2명 이하이면 즉시 시작
                if (getAlivePlayers().size() <= 2) startShowdown();
            }
        }, SHOWDOWN_AFTER_SEC * 20L);

        Bukkit.broadcastMessage("§c[게임] §f시작!");
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

        phase = Phase.SHOWDOWN;

        Player a = left.get(0);
        Player b = left.get(1);

        // 관전자는 자동 스펙 처리(원하면)
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!alive.contains(p.getUniqueId())) {
                p.setGameMode(GameMode.SPECTATOR);
            }
        }

        a.teleport(showdownLocA);
        b.teleport(showdownLocB);

        Bukkit.broadcastMessage("§6[결투] §f남은 두 명이 결투장으로 이동합니다!");
        World w = showdownLocA.getWorld();
        if (w != null) w.playSound(showdownLocA, Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.2f);
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
        stopAllTasks();

        Bukkit.broadcastMessage("§b[우승] §e" + winner.getName() + "§f 님이 우승했습니다!");
        World w = winner.getWorld();
        w.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);

        // (선택) 전원 로비 텔포/초기화
    }

    private void endGameNoWinner() {
        phase = Phase.ENDED;
        stopAllTasks();
        Bukkit.broadcastMessage("§7[게임] 우승자 없이 종료되었습니다.");
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
            if (p != null && p.isOnline()) {   // ✅ isDead() 체크 제거
                list.add(p);
            }
        }
        return list;
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        // 탈락자는 관전(리스폰 위치는 상관없지만 깔끔하게 스폰으로)
        if (isRunning() && !alive.contains(id)) {
            e.setRespawnLocation(p.getWorld().getSpawnLocation().clone().add(0.5, 1, 0.5));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) p.setGameMode(GameMode.SPECTATOR);
            }, 1L);
            return;
        }

        // 목숨 남아있으면 랜덤 리스폰 적용
        Location resp = pendingRespawn.remove(id);
        if (resp != null) {
            e.setRespawnLocation(resp);
        }
    }

    // ===== 사망/퇴장 처리 =====
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        UUID id = dead.getUniqueId();

        if (!isRunning()) return;
        if (!alive.contains(id)) return; // 이미 탈락 처리된 사람은 무시

        int left = lives.getOrDefault(id, START_LIVES) - 1;
        lives.put(id, left);

        // ✅ 채팅 알림(죽을 때마다)
        if (left > 0) {
            Bukkit.broadcastMessage("§e[사망] §f" + dead.getName() + "§7 사망! 남은 목숨: §c" + left);

            // 첫/두번째 죽음 => 랜덤 리스폰 예약
            World w = dead.getWorld();
            Location resp = pickRandomRespawn(w);
            pendingRespawn.put(id, resp);

        } else {
            // ✅ 탈락
            Bukkit.broadcastMessage("§c[탈락] §f" + dead.getName() + "§7 님이 탈락했습니다!");
            alive.remove(id);
            pendingRespawn.remove(id);

            // 리스폰 후 관전으로 전환(바로 바꾸면 일부 서버에서 꼬일 수 있어 1틱 딜레이)
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (dead.isOnline()) dead.setGameMode(GameMode.SPECTATOR);
            }, 1L);
        }

        updateScoreboardAll();

        // 쇼다운 조건(12분 이후 + 2명 이하)
        if (phase == Phase.RUNNING && showdownEnabled && getAlivePlayers().size() <= 2) {
            Bukkit.getScheduler().runTask(plugin, this::startShowdown);
        }

        // 우승 체크
        Bukkit.getScheduler().runTask(plugin, this::checkWinner);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        alive.remove(e.getPlayer().getUniqueId());
        if (isRunning()) Bukkit.getScheduler().runTask(plugin, this::checkWinner);
    }
}