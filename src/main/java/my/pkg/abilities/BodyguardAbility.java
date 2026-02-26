package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.Location;
import org.bukkit.GameMode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BodyguardAbility implements Ability, Listener {

    private final JavaPlugin plugin;

    // 플레이어별 보호대상
    private static final Map<UUID, UUID> targetMap = new ConcurrentHashMap<>();

    // 최대체력 모디파이어(중복 방지용 고정 UUID)
    private final NamespacedKey HP_MOD_KEY;
    private static final String HP_MOD_NAME = "bodyguard_hp_bonus";

    private static boolean listenerRegistered = false;

    // 버프 값
    private static final double BONUS_HP = 6.0; // 3칸 = 3 hearts = 6 health
    private static final int SPEED_AMP = 0;     // Speed I
    private static final int LONG_TICKS = 20 * 60 * 60; // 1시간(사실상 지속)

    private static final int RANGE = 20;
    private static final int CHECK_PERIOD_TICKS = 20; // 1초마다 체크
    private static boolean rangeTaskStarted = false;

    public BodyguardAbility(JavaPlugin plugin) {
        this.plugin = plugin;
        this.HP_MOD_KEY = new NamespacedKey(plugin, "bodyguard_hp_bonus");

        if (!listenerRegistered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            listenerRegistered = true;
        }

        if (!rangeTaskStarted) {
            startRangeCheckTask();
            rangeTaskStarted = true;
        }
    }

    @Override
    public String id() { return "bodyguard"; }

    @Override
    public String name() { return "보디가드"; }

    @Override
    public int cooldownSeconds() { return 0; } // 패시브

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("보디가드 : 자신을 제외한 플레이어중 랜덤으로 한명이 보호대상이됩니다. 보호대상이 살아있을때 각종 버프를 받지만, 보호대상이 죽으면 버프가 사라집니다.");
        Player target = pickRandomOtherPlayer(player);
        if (target == null) {
            player.sendMessage("§7[보디가드] 현재 보호대상을 지정할 수 없습니다(다른 플레이어 없음).");
            targetMap.remove(player.getUniqueId());
            removeBuff(player);
            return;
        }

        targetMap.put(player.getUniqueId(), target.getUniqueId());
        player.sendMessage("§a[보디가드] 보호대상: §e" + target.getName());
        applyBuff(player, target);
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        targetMap.remove(player.getUniqueId());
        removeBuff(player);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        UUID targetId = targetMap.get(player.getUniqueId());
        if (targetId == null) {
            player.sendMessage("§7[보디가드] 보호대상이 없습니다.");
            return false;
        }

        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline() || target.isDead()) {
            player.sendMessage("§7[보디가드] 보호대상이 현재 오프라인이거나 사망했습니다.");
            return false;
        }

        if (player.getWorld() != target.getWorld()) {
            player.sendMessage("§c[보디가드] 보호대상이 다른 월드에 있습니다.");
            return false;
        }

        Location my = player.getLocation();
        Location t = target.getLocation();

        int dist = (int) Math.floor(my.distance(t));
        String dir = getDirection8(my, t);

        player.sendMessage("§a[보디가드] 보호대상 위치: §e" + target.getName()
                + " §7(거리 " + dist + "m, 방향 " + dir + ")");

        return true; // “사용 성공” 처리
    }
    private String getDirection8(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();

        // 월드 좌표 기준: +X=동, -X=서, +Z=남, -Z=북
        double angle = Math.toDegrees(Math.atan2(dz, dx)); // -180~180
        // 0=동, 90=남, 180/-180=서, -90=북

        if (angle < 0) angle += 360;

        String[] dirs = {"동", "남동", "남", "남서", "서", "북서", "북", "북동"};
        int idx = (int) Math.round(angle / 45.0) % 8;
        return dirs[idx];
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        UUID deadId = dead.getUniqueId();

        // 죽은 사람이 누군가의 보호대상인지 확인
        for (Map.Entry<UUID, UUID> e : new HashSet<>(targetMap.entrySet())) {
            UUID bodyguardId = e.getKey();
            UUID targetId = e.getValue();
            if (!deadId.equals(targetId)) continue;

            Player bodyguard = Bukkit.getPlayer(bodyguardId);
            if (bodyguard == null) continue;

            // 보호대상 죽음 -> 버프 제거
            bodyguard.sendMessage("§c[보디가드] 보호대상(§e" + dead.getName() + "§c)이 죽었습니다. 버프가 사라집니다.");
            removeBuff(bodyguard);
            // 대상은 유지(재지정 없음). 원하면 여기서 targetMap.remove(bodyguardId) 해도 됨.
        }
    }

    private Player pickRandomOtherPlayer(Player self) {
        List<Player> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals(self)) continue;
            if (p.isDead()) continue;
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            list.add(p);
        }
        if (list.isEmpty()) return null;
        return list.get(new Random().nextInt(list.size()));
    }

    private void applyBuff(Player owner, Player target) {
        if (target == null || target.isDead() || !target.isOnline()) {
            removeBuff(owner);
            return;
        }

        var attr = owner.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            // ✅ 1.21+ : Key로 식별해서 제거
            attr.getModifiers().stream()
                    .filter(m -> HP_MOD_KEY.equals(m.getKey()))
                    .forEach(attr::removeModifier);

            // ✅ 1.21+ : NamespacedKey 기반 생성자 사용 (Spigot 호환 위해 slotGroup 포함)
            attr.addModifier(new AttributeModifier(
                    HP_MOD_KEY,
                    BONUS_HP,
                    AttributeModifier.Operation.ADD_NUMBER,
                    EquipmentSlotGroup.ANY
            ));

            double max = owner.getMaxHealth();
            if (owner.getHealth() > max) owner.setHealth(max);
        }
    }

    private void removeBuff(Player owner) {
        var attr = owner.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            attr.getModifiers().stream()
                    .filter(m -> HP_MOD_KEY.equals(m.getKey()))
                    .forEach(attr::removeModifier);

            double max = owner.getMaxHealth();
            if (owner.getHealth() > max) owner.setHealth(max);
        }

        owner.removePotionEffect(PotionEffectType.SPEED);
    }
    private void startRangeCheckTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Map.Entry<UUID, UUID> e : new HashSet<>(targetMap.entrySet())) {
                Player bodyguard = Bukkit.getPlayer(e.getKey());
                Player target = Bukkit.getPlayer(e.getValue());

                if (bodyguard == null || !bodyguard.isOnline()) continue;

                // 대상이 없거나 오프라인/죽음 -> 신속 제거
                if (target == null || !target.isOnline() || target.isDead()) {
                    bodyguard.removePotionEffect(PotionEffectType.SPEED);
                    continue;
                }

                // 월드 다르면 근처로 취급 X
                if (bodyguard.getWorld() != target.getWorld()) {
                    bodyguard.removePotionEffect(PotionEffectType.SPEED);
                    continue;
                }

                // ✅ 20블록 이내일 때만 신속 적용
                double distSq = bodyguard.getLocation().distanceSquared(target.getLocation());
                if (distSq <= (RANGE * RANGE)) {
                    // 짧게 갱신(2~3초)하는 방식이 안전함
                    bodyguard.addPotionEffect(new PotionEffect(
                            PotionEffectType.SPEED,
                            20 * 3, // 3초
                            SPEED_AMP,
                            true, false, true
                    ));
                } else {
                    bodyguard.removePotionEffect(PotionEffectType.SPEED);
                }
            }
        }, 0L, CHECK_PERIOD_TICKS);
    }
}