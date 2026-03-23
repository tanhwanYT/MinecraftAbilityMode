package my.pkg.abilities;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import my.pkg.AbilitySystem;

public class ShadowStepAbility implements Ability, Listener {

    // ===== 밸런스 설정 =====
    private static final int FREEZE_TICKS = 20;       // 1초
    private static final double RANGE = 8.0;          // 타겟팅 거리
    private static final double BEHIND_DISTANCE = 1.5; // 대상 뒤로 이동 거리

    // ===== 상태 저장 =====
    private static final Map<UUID, FrozenData> frozenPlayers = new ConcurrentHashMap<>();
    private static final Map<UUID, BukkitTask> returnTasks = new ConcurrentHashMap<>();
    private static boolean listenerRegistered = false;

    @Override
    public String id() {
        return "shadowstep";
    }

    @Override
    public String name() {
        return "섀도우 스텝";
    }

    @Override
    public int cooldownSeconds() {
        return 20;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("§5[섀도우 스텝] §f바라보는 엔티티의 뒤로 이동합니다.");
        player.sendMessage("§7- 대상은 1초간 이동과 시야가 고정됩니다.");
        player.sendMessage("§7- 1초 후 원래 위치로 돌아옵니다.");

        if (!listenerRegistered) {
            system.getPlugin().getServer().getPluginManager().registerEvents(this, system.getPlugin());
            listenerRegistered = true;
        }
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        UUID uuid = player.getUniqueId();

        BukkitTask task = returnTasks.remove(uuid);
        if (task != null) task.cancel();

        frozenPlayers.remove(uuid);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        LivingEntity target = getTargetEntity(player, RANGE);

        if (target == null) {
            player.sendMessage("§c[섀도우 스텝] 바라보는 대상이 없습니다.");
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 0.8f);
            return false; // 발동 실패, 쿨타임 소모 X
        }

        if (target == player) {
            player.sendMessage("§c[섀도우 스텝] 자신에게는 사용할 수 없습니다.");
            return false;
        }

        Location originalLocation = player.getLocation().clone();
        Location behindLocation = getBehindLocation(target);

        // 대상 고정
        freezeTarget(system, target);

        // 시전자 이동
        player.teleport(behindLocation);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.1f);
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.9f);

        // 기존 복귀 작업이 있으면 취소
        BukkitTask oldTask = returnTasks.remove(player.getUniqueId());
        if (oldTask != null) oldTask.cancel();

        // 1초 뒤 원래 자리 복귀
        BukkitTask returnTask = system.getPlugin().getServer().getScheduler().runTaskLater(system.getPlugin(), () -> {
            if (!player.isOnline()) return;

            player.teleport(originalLocation);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.95f);

            unfreezeTarget(target);
            returnTasks.remove(player.getUniqueId());
        }, FREEZE_TICKS);

        returnTasks.put(player.getUniqueId(), returnTask);
        return true;
    }

    // ===== 내부 로직 =====

    private LivingEntity getTargetEntity(Player player, double range) {
        RayTraceResult result = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                range,
                entity -> entity instanceof LivingEntity && entity != player
        );

        if (result == null) return null;

        Entity hit = result.getHitEntity();
        if (hit instanceof LivingEntity living) {
            return living;
        }
        return null;
    }

    private Location getBehindLocation(LivingEntity target) {
        Location loc = target.getLocation().clone();

        Vector dir = loc.getDirection().clone().setY(0);
        if (dir.lengthSquared() == 0) {
            dir = new Vector(0, 0, 1);
        }
        dir.normalize();

        // 대상 뒤쪽
        Location behind = loc.clone().subtract(dir.multiply(BEHIND_DISTANCE));

        // 방향은 대상을 바라보도록
        Vector lookAtTarget = target.getLocation().toVector().subtract(behind.toVector());
        behind.setDirection(lookAtTarget);

        return behind;
    }

    private void freezeTarget(AbilitySystem system, LivingEntity target) {
        if (target instanceof Player p) {
            Location fixed = p.getLocation().clone();

            BukkitTask old = null;
            FrozenData oldData = frozenPlayers.remove(p.getUniqueId());
            if (oldData != null) old = oldData.releaseTask;
            if (old != null) old.cancel();

            BukkitTask releaseTask = system.getPlugin().getServer().getScheduler().runTaskLater(system.getPlugin(), () -> {
                frozenPlayers.remove(p.getUniqueId());
            }, FREEZE_TICKS);

            frozenPlayers.put(p.getUniqueId(), new FrozenData(fixed, releaseTask));
            p.sendMessage("§c[상태이상] 1초간 움직일 수 없습니다!");
        } else if (target instanceof Mob mob) {
            mob.setAI(false);

            system.getPlugin().getServer().getScheduler().runTaskLater(system.getPlugin(), () -> {
                if (mob.isValid() && !mob.isDead()) {
                    mob.setAI(true);
                }
            }, FREEZE_TICKS);
        }
    }

    private void unfreezeTarget(LivingEntity target) {
        if (target instanceof Player p) {
            FrozenData data = frozenPlayers.remove(p.getUniqueId());
            if (data != null && data.releaseTask != null) {
                data.releaseTask.cancel();
            }
        } else if (target instanceof Mob mob) {
            if (mob.isValid() && !mob.isDead()) {
                mob.setAI(true);
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        FrozenData data = frozenPlayers.get(event.getPlayer().getUniqueId());
        if (data == null) return;

        Location to = event.getTo();
        if (to == null) return;

        Location fixed = data.fixedLocation.clone();
        event.setTo(fixed);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        FrozenData data = frozenPlayers.remove(event.getPlayer().getUniqueId());
        if (data != null && data.releaseTask != null) {
            data.releaseTask.cancel();
        }

        BukkitTask task = returnTasks.remove(event.getPlayer().getUniqueId());
        if (task != null) task.cancel();
    }

    private static class FrozenData {
        private final Location fixedLocation;
        private final BukkitTask releaseTask;

        private FrozenData(Location fixedLocation, BukkitTask releaseTask) {
            this.fixedLocation = fixedLocation;
            this.releaseTask = releaseTask;
        }
    }
}