package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SniperAbility implements Ability, Listener {
    private final JavaPlugin plugin;
    private static boolean listenerRegistered = false;

    // ===== 밸런스 =====
    private static final double RANGE = 75.0;         // 사거리 (늘림)
    private static final double DAMAGE = 14.0;        // 대미지 (늘림) - 하트 7칸
    private static final int COOLDOWN = 25;           // 쿨타임 (늘림)

    // ===== 시즈모드(조준) =====
    private static final int AIM_TICKS = 20 * 3;      // 3초 조준
    private static final int AIM_UPDATE_PERIOD = 1;   // 매 틱 조준선 갱신
    private static final double STEP = 0.35;          // 라인 촘촘함

    // ===== 발사 후 잔상 =====
    private static final int TRAIL_TICKS = 12;        // 발사 후 하얀 잔상(기존 느낌 유지)
    private static final Particle.DustOptions RED = new Particle.DustOptions(Color.RED, 1.2f);

    // 조준 중 상태 저장
    private final Map<UUID, AimState> aiming = new ConcurrentHashMap<>();

    private static class AimState {
        BukkitTask task;
        int leftTicks;
        Location lastAimLoc;          // 마지막 조준 위치
        LivingEntity lastAimEntity;   // 마지막으로 조준된 엔티티(있으면)
    }

    public SniperAbility(JavaPlugin plugin) {
        this.plugin = plugin;

        if (!listenerRegistered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            listenerRegistered = true;
        }
    }

    @Override
    public String id() { return "sniper"; }

    @Override
    public String name() { return "스나이퍼"; }

    @Override
    public int cooldownSeconds() { return COOLDOWN; }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("스나이퍼 : 우클릭 시 시즈모드(3초 조준)로 돌입합니다. 조준 중에는 빨간 잔상이 보이고, 3초 뒤 강한 탄환이 발사됩니다.");
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        UUID id = player.getUniqueId();

        // 이미 조준 중이면 중복 방지 (쿨 소모 X)
        if (aiming.containsKey(id)) {
            player.sendActionBar("§7[스나이퍼] 이미 조준 중...");
            return false;
        }

        AimState st = new AimState();
        st.leftTicks = AIM_TICKS;
        aiming.put(id, st);

        player.sendMessage("§c[스나이퍼] 시즈모드 돌입! 3초 조준...");
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.6f);

        // 매 틱(혹은 0.5초)마다 조준선 갱신
        st.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || player.isDead()) {
                stopAiming(id);
                return;
            }

            // 매 틱 현재 조준 위치 계산 + 빨간 조준선 표시
            AimResult aim = computeAim(player);
            if (aim.aimLoc != null) {
                st.lastAimLoc = aim.aimLoc;
                st.lastAimEntity = aim.hitEntity;
                spawnRedHitMarker(st.lastAimLoc);
            }

            int sec = (st.leftTicks + 19) / 20;
            player.sendActionBar("§c[시즈모드] §f조준 중... §c" + sec + "s");

            st.leftTicks -= AIM_UPDATE_PERIOD;
            if (st.leftTicks <= 0) {
                // 3초 끝 -> 발사
                Location fireAt = st.lastAimLoc;
                LivingEntity hitEnt = st.lastAimEntity;

                stopAiming(id); // 상태/작업 정리 먼저

                if (fireAt == null) {
                    return;
                }

                fire(player, fireAt, hitEnt);
            }
        }, 0L, AIM_UPDATE_PERIOD);

        return true; // 여기서 쿨타임 시작됨(AbilitySystem이 처리)
    }

    private void stopAiming(UUID id) {
        AimState st = aiming.remove(id);
        if (st != null && st.task != null) st.task.cancel();
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        AimState st = aiming.get(p.getUniqueId());
        if (st == null) return; // 조준 중이 아니면 패스

        if (event.getTo() == null) return;

        // ✅ X/Z만 고정 (Y는 허용해서 낙하/점프 자연스럽게, 플라이킥 방지)
        boolean movedXZ = event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getZ() != event.getTo().getZ();

        if (movedXZ) {
            Location to = event.getTo().clone();
            to.setX(event.getFrom().getX());
            to.setZ(event.getFrom().getZ());
            event.setTo(to);
        }
    }

    // ====== 조준 계산: 엔티티/블록 중 가까운 쪽 ======
    private static class AimResult {
        final Location aimLoc;
        final LivingEntity hitEntity;
        AimResult(Location aimLoc, LivingEntity hitEntity) {
            this.aimLoc = aimLoc;
            this.hitEntity = hitEntity;
        }
    }

    private AimResult computeAim(Player player) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        // 블록 히트
        RayTraceResult blockHit = world.rayTraceBlocks(eye, dir, RANGE);
        double blockDist = Double.POSITIVE_INFINITY;
        Location blockLoc = null;
        if (blockHit != null && blockHit.getHitPosition() != null) {
            Vector p = blockHit.getHitPosition();
            blockLoc = new Location(world, p.getX(), p.getY(), p.getZ()).add(dir.clone().multiply(-0.2));
            blockDist = eye.toVector().distance(p);
        }

        // 엔티티 히트(플레이어 자신 제외)
        RayTraceResult entHit = world.rayTraceEntities(eye, dir, RANGE, 0.3,
                e -> e instanceof LivingEntity && e != player);

        double entDist = Double.POSITIVE_INFINITY;
        Location entLoc = null;
        LivingEntity ent = null;

        if (entHit != null && entHit.getHitEntity() instanceof LivingEntity le) {
            ent = le;
            if (entHit.getHitPosition() != null) {
                Vector p = entHit.getHitPosition();
                entLoc = new Location(world, p.getX(), p.getY(), p.getZ());
                entDist = eye.toVector().distance(p);
            } else {
                entLoc = le.getLocation().add(0, le.getHeight() * 0.5, 0);
                entDist = eye.distance(entLoc);
            }
        }

        // 더 가까운 쪽 선택
        if (entLoc != null && entDist <= blockDist) {
            return new AimResult(entLoc, ent);
        }

        if (blockLoc != null) {
            return new AimResult(blockLoc, null);
        }

        // 아무것도 없으면 최대거리 지점
        Location far = eye.clone().add(dir.multiply(RANGE));
        return new AimResult(far, null);
    }

    // ====== 발사 ======
    private void fire(Player player, Location boomLoc, LivingEntity hitEntity) {
        World world = player.getWorld();

        // 1) 폭죽 소환
        Firework fw = world.spawn(boomLoc, Firework.class, f -> {
            FireworkMeta meta = f.getFireworkMeta();
            meta.setPower(0);
            meta.clearEffects();
            meta.addEffect(
                    FireworkEffect.builder()
                            .with(FireworkEffect.Type.BALL)
                            .withFlicker()
                            .build()
            );
            f.setFireworkMeta(meta);
            f.setSilent(true);
        });

        // 2) 즉시 폭발
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!fw.isDead()) fw.detonate();
        }, 1L);

        // 3) 대미지 (엔티티 조준 성공 시)
        if (hitEntity != null && !hitEntity.isDead()) {
            hitEntity.damage(DAMAGE, player);
        }

        // 4) 발사 후 하얀 잔상(기존 컨셉)
        startWhiteTrail(player, boomLoc);

        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.3f);
        player.sendMessage("§b[스나이퍼] §f발사!");
    }

    private void spawnRedHitMarker(Location at) {
        World w = at.getWorld();
        if (w == null) return;

        // 시야를 안 가리게: 소량 + 작은 퍼짐
        Location p = at.clone().add(0, 0.05, 0);

        // 중심 점
        w.spawnParticle(Particle.DUST, p, 2, 0.02, 0.02, 0.02, 0.0, RED);

        // 작은 링 느낌(너무 무겁지 않게 8점)
        double r = 0.25;
        for (int i = 0; i < 8; i++) {
            double ang = (Math.PI * 2.0) * i / 8.0;
            Location ring = p.clone().add(Math.cos(ang) * r, 0.0, Math.sin(ang) * r);
            w.spawnParticle(Particle.DUST, ring, 1, 0.01, 0.01, 0.01, 0.0, RED);
        }
    }

    // ====== 발사 후 하얀 잔상 ======
    private void startWhiteTrail(Player player, Location to) {
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!player.isOnline()) { cancel(); return; }

                Location curFrom = player.getEyeLocation().clone();
                spawnWhiteLine(curFrom, to);

                t++;
                if (t >= TRAIL_TICKS) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void spawnWhiteLine(Location from, Location to) {
        World w = from.getWorld();
        if (w == null) return;

        Vector diff = to.toVector().subtract(from.toVector());
        double dist = diff.length();
        if (dist <= 0.01) return;

        Vector step = diff.normalize().multiply(STEP);
        int points = (int) Math.ceil(dist / STEP);

        Location p = from.clone();
        for (int i = 0; i < points; i++) {
            w.spawnParticle(Particle.END_ROD, p, 1, 0, 0, 0, 0);
            p.add(step);
        }
    }
}