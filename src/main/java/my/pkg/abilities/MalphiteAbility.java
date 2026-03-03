package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public class MalphiteAbility implements Ability {

    // ===== 밸런스/연출 설정 =====
    private static final int DASH_TICKS = 20;          // 1초
    private static final int SPEED_AMP = 5;

    private static final double SLOW_RANGE = 6.0;      // 주변 플레이어 슬로우 범위
    private static final int SLOW_TICKS = 25;          // 약간 여유 있게(진입자 커버)
    private static final int SLOW_AMP = 1;             // Slowness II 정도 (0=1레벨, 1=2레벨)

    private static final int BREAK_RADIUS_XZ = 1;      // x,z 파괴 반경(1이면 3x3)
    private static final int BREAK_HEIGHT = 2;         // y방향 파괴(발 기준 0~1 정도)
    private static final int BREAK_FORWARD = 1;        // 진행 방향으로 한 칸 더 파괴해서 뚫고 가는 느낌

    private static final double EXPLODE_RANGE = 3.0;   // 폭발 판정 범위
    private static final double DAMAGE = 4.0;
    private static final double LAUNCH_Y = 1.1;

    @Override public String id() { return "malphite"; }
    @Override public String name() { return "말파이트"; }
    @Override public int cooldownSeconds() { return 30; }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("말파이트 : 네더의 별 우클릭 시 1초간 돌진합니다. 돌진 중 주변 플레이어가 느려지고, 앞을 뚫고 나갑니다. 1초 후 주변을 띄우며 피해를 줍니다.");
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        // 1초 돌진 속도
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.SPEED,
                DASH_TICKS,
                SPEED_AMP,
                false,
                false
        ));

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.8f, 1.2f);

        // 돌진 중(1초) 매틱 처리: 슬로우 + 블럭 파괴
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    cancel();
                    return;
                }

                // 1) 주변 플레이어 느려지게(이속 뺏는 느낌) - 진입자도 잡히도록 매틱 갱신
                applySlowAura(player);

                // 2) 돌진 중 주변 X/Z 블럭 파괴 (앞으로 길 뚫는 느낌 포함)
                breakBlocksAround(player);

                if (t >= DASH_TICKS) {
                    cancel();
                    // 1초 후 폭발/에어본
                    doExplosionSlam(player);
                    return;
                }

                t++;
            }
        }.runTaskTimer(system.getPlugin(), 0L, 1L);

        return true;
    }

    private void applySlowAura(Player player) {
        Location center = player.getLocation();
        for (Player p : player.getWorld().getPlayers()) {
            if (p.equals(player)) continue;
            if (p.isDead()) continue;
            if (p.getLocation().distanceSquared(center) > (SLOW_RANGE * SLOW_RANGE)) continue;

            p.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS,
                    SLOW_TICKS,
                    SLOW_AMP,
                    false,
                    false,
                    true
            ));
        }

        // 가시성(선택): 주변에 먼지/스모크
        player.getWorld().spawnParticle(Particle.DUST, center.clone().add(0, 1.0, 0),
                8, 0.6, 0.5, 0.6, 0.0, new Particle.DustOptions(Color.GRAY, 1.5f));
    }

    private void breakBlocksAround(Player player) {
        Location loc = player.getLocation();
        World w = player.getWorld();

        // 진행방향으로 한 칸 더(뚫고 가는 느낌)
        Vector dir = loc.getDirection().setY(0).normalize();
        int fx = (int) Math.round(dir.getX() * BREAK_FORWARD);
        int fz = (int) Math.round(dir.getZ() * BREAK_FORWARD);

        int baseX = loc.getBlockX();
        int baseY = loc.getBlockY();
        int baseZ = loc.getBlockZ();

        // 발밑~머리 정도(0~1) + 앞쪽 가산
        for (int dx = -BREAK_RADIUS_XZ; dx <= BREAK_RADIUS_XZ; dx++) {
            for (int dz = -BREAK_RADIUS_XZ; dz <= BREAK_RADIUS_XZ; dz++) {
                for (int dy = 0; dy < BREAK_HEIGHT; dy++) {
                    Block b = w.getBlockAt(baseX + dx + fx, baseY + dy, baseZ + dz + fz);

                    if (!isBreakable(b)) continue;

                    // 드랍 없이 파괴(서버/밸런스 안정)
                    b.setType(Material.AIR, false);

                    // 파괴 이펙트(과하면 렉이라 적당히)
                    w.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5),
                            6, 0.2, 0.2, 0.2, 0.0, Material.STONE.createBlockData());
                    w.playSound(b.getLocation(), Sound.BLOCK_STONE_BREAK, 0.2f, 1.2f);
                }
            }
        }
    }

    private boolean isBreakable(Block b) {
        Material m = b.getType();

        if (m.isAir()) return false;
        if (m == Material.BEDROCK || m == Material.BARRIER) return false;
        if (m == Material.END_PORTAL || m == Material.END_PORTAL_FRAME) return false;
        if (m == Material.NETHER_PORTAL) return false;

        // ✅ 흙/땅 계열 보호
        if (m == Material.DIRT
                || m == Material.GRASS_BLOCK
                || m == Material.COARSE_DIRT
                || m == Material.PODZOL
                || m == Material.MYCELIUM
                || m == Material.ROOTED_DIRT
                || m == Material.DIRT_PATH) {
            return false;
        }

        // ✅ 돌/구운돌(석재) 계열 보호
        if (m == Material.STONE
                || m == Material.SMOOTH_STONE
                || m == Material.STONE_BRICKS
                || m == Material.CRACKED_STONE_BRICKS
                || m == Material.MOSSY_STONE_BRICKS
                || m == Material.CHISELED_STONE_BRICKS) {
            return false;
        }

        // 컨테이너/특수 블록 보호
        BlockState state = b.getState();
        if (state instanceof org.bukkit.inventory.InventoryHolder) return false;

        return true;
    }

    private void doExplosionSlam(Player player) {
        if (!player.isOnline() || player.isDead()) return;

        Location center = player.getLocation();

        player.getWorld().spawnParticle(Particle.EXPLOSION, center, 1, 0, 0, 0, 0);
        player.getWorld().playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.0f);

        for (Entity entity : player.getWorld().getNearbyEntities(center, EXPLODE_RANGE, EXPLODE_RANGE, EXPLODE_RANGE)) {
            if (!(entity instanceof LivingEntity target)) continue;
            if (target.equals(player)) continue;

            target.damage(DAMAGE, player);

            Vector v = target.getVelocity();
            v.setY(LAUNCH_Y);
            target.setVelocity(v);
        }
    }
}