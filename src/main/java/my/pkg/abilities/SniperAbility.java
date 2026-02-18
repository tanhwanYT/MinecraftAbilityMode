package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Bukkit;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

public class SniperAbility implements Ability {

    private final JavaPlugin plugin;

    // 설정값
    private static final double RANGE = 40.0;      // 사거리
    private static final int TRAIL_TICKS = 12;     // 잔상 유지 시간(틱) (12틱 ≒ 0.6초)
    private static final double STEP = 0.35;       // 잔상 라인 촘촘함

    public SniperAbility(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() { return "sniper"; }

    @Override
    public String name() { return "스나이퍼"; }

    @Override
    public int cooldownSeconds() { return 10; }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("§b[스나이퍼] §f네더스타 우클릭 시 바라보는 방향에 폭죽 + 잔상!");
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        World world = player.getWorld();

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        // ✅ 블록에 맞으면 거기, 아니면 최대거리 지점
        RayTraceResult hit = world.rayTraceBlocks(eye, dir, RANGE);
        Location boomLoc;
        if (hit != null && hit.getHitPosition() != null) {
            Vector p = hit.getHitPosition();
            boomLoc = new Location(world, p.getX(), p.getY(), p.getZ());
            // 블록 안에 박히는 느낌 방지로 살짝 뒤로
            boomLoc.add(dir.clone().multiply(-0.2));
        } else {
            boomLoc = eye.clone().add(dir.multiply(RANGE));
        }

        // ✅ 작은 폭죽 소환
        Firework fw = world.spawn(boomLoc, Firework.class, f -> {
            FireworkMeta meta = f.getFireworkMeta();
            meta.setPower(0); // 작은 느낌
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

        // ✅ 거의 즉시 터뜨리기 (1틱 뒤)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!fw.isDead()) fw.detonate();
        }, 1L);

        // ✅ 잔상(플레이어 -> 폭죽) 라인
        startTrail(player, boomLoc);

        return true;
    }

    private void startTrail(Player player, Location to) {
        Location from = player.getEyeLocation().clone();

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!player.isOnline()) { cancel(); return; }

                // 매 틱마다 살짝 흔들리는 “잔상” 느낌을 위해 from은 현재 시점 눈 위치로 갱신
                Location curFrom = player.getEyeLocation().clone();
                spawnLine(curFrom, to);

                t++;
                if (t >= TRAIL_TICKS) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void spawnLine(Location from, Location to) {
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
