package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
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
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HitmanAbility implements Ability, Listener {

    private final JavaPlugin plugin;

    // 청부대상
    private static final Map<UUID, UUID> targetMap = new ConcurrentHashMap<>();
    // 누적 스택
    private static final Map<UUID, Integer> stacks = new ConcurrentHashMap<>();

    private static boolean listenerRegistered = false;

    // 밸런스
    private static final double HP_PER_STACK = 2.0;     // 1칸 = 2 health
    private static final int LONG_TICKS = 20 * 60 * 60; // 사실상 지속
    private static final int MAX_STRENGTH_LEVEL = 5;    // Strength V까지 제한(원하면 수정)

    // ✅ 1.21+ AttributeModifier는 NamespacedKey로 식별
    private final NamespacedKey hpKey;

    public HitmanAbility(JavaPlugin plugin) {
        this.plugin = plugin;
        this.hpKey = new NamespacedKey(plugin, "hitman_hp_bonus");

        if (!listenerRegistered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            listenerRegistered = true;
        }
    }

    @Override
    public String id() { return "hitman"; }

    @Override
    public String name() { return "청부업자"; }

    @Override
    public int cooldownSeconds() { return 0; } // 패시브

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("청부업자 : 청부대상이 자신을 제외한 플레이어중 랜덤으로 한명이 지정됩니다. 청부대상을 직접 죽이면 버프를 얻습니다. 청부대상이 죽을때마다 새 대상이 지정되며 버프또한 중첩됩니다.");
        stacks.putIfAbsent(player.getUniqueId(), 0);
        pickNewTarget(player, true);
        applyStackBuff(player);
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        targetMap.remove(player.getUniqueId());
        stacks.remove(player.getUniqueId());
        removeBuff(player);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        UUID t = targetMap.get(player.getUniqueId());
        if (t == null) {
            player.sendMessage("§7[청부업자] 현재 청부대상이 없습니다.");
            return false;
        }

        Player target = Bukkit.getPlayer(t);
        if (target == null || !target.isOnline() || target.isDead()) {
            player.sendMessage("§7[청부업자] 청부대상이 오프라인/사망 상태입니다.");
            return false;
        }

        // ✅ 나침반이 가리킬 위치 설정(같은 월드에서만 의미있음)
        if (player.getWorld().equals(target.getWorld())) {
            player.setCompassTarget(target.getLocation());
        }

        // ✅ 방향/거리 안내
        Location pl = player.getLocation();
        Location tl = target.getLocation();

        String worldInfo = player.getWorld().equals(target.getWorld())
                ? "§a같은 월드"
                : "§c다른 월드(추적 불가)";

        double dist = player.getWorld().equals(target.getWorld())
                ? pl.distance(tl)
                : -1;

        String dir = player.getWorld().equals(target.getWorld())
                ? getDirectionName(pl, tl)
                : "-";

        player.sendMessage("§c[청부업자] 현재 청부대상: §e" + target.getName());
        player.sendMessage("§7- 월드: " + worldInfo);
        if (dist >= 0) {
            player.sendMessage("§7- 거리: §f" + (int) dist + "m §7/ 방향: §f" + dir + " §7(나침반 갱신)");
        } else {
            player.sendMessage("§7- 대상이 다른 월드라 거리/방향을 표시할 수 없습니다.");
        }

        return false; // 패시브라 실제 쿨/발동은 없음
    }

    private String getDirectionName(Location from, Location to) {
        Vector dir = to.toVector().subtract(from.toVector());
        double angle = Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ())); // MC 기준 보정
        angle = (angle + 360) % 360;

        if (angle < 22.5) return "북";
        if (angle < 67.5) return "북동";
        if (angle < 112.5) return "동";
        if (angle < 157.5) return "남동";
        if (angle < 202.5) return "남";
        if (angle < 247.5) return "남서";
        if (angle < 292.5) return "서";
        if (angle < 337.5) return "북서";
        return "북";
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        Player killer = dead.getKiller(); // "직접" 처치한 경우(근접/투사체 등)

        UUID deadId = dead.getUniqueId();

        // 1) 누군가의 청부대상이 죽었는지 확인 -> 새 대상 지정
        for (UUID hitmanId : new HashSet<>(targetMap.keySet())) {
            UUID curTarget = targetMap.get(hitmanId);
            if (curTarget == null || !curTarget.equals(deadId)) continue;

            Player hitman = Bukkit.getPlayer(hitmanId);
            if (hitman != null) {
                hitman.sendMessage("§7[청부업자] 청부대상(§e" + dead.getName() + "§7)이 사망. 새 대상을 지정합니다.");
                pickNewTarget(hitman, false);
            } else {
                targetMap.remove(hitmanId);
            }
        }

        // 2) “직접 죽인 플레이어”가 청부업자고, 그 상대가 내 청부대상이면 스택 증가
        if (killer != null) {
            UUID killerId = killer.getUniqueId();
            UUID targetId = targetMap.get(killerId);

            if (targetId != null && targetId.equals(deadId)) {
                int cur = stacks.getOrDefault(killerId, 0) + 1;
                stacks.put(killerId, cur);

                killer.sendMessage("§a[청부업자] §f목표 처치 성공! 스택 §e" + cur + "§f (+최대체력/힘)");
                applyStackBuff(killer);
            }
        }
    }

    private void pickNewTarget(Player self, boolean first) {
        Player t = pickRandomOtherPlayer(self);
        if (t == null) {
            targetMap.remove(self.getUniqueId());
            self.sendMessage("§7[청부업자] 대상 지정 실패(다른 플레이어 없음).");
            return;
        }

        targetMap.put(self.getUniqueId(), t.getUniqueId());
        self.sendMessage((first ? "§c[청부업자] 청부대상 지정: §e" : "§c[청부업자] 새 청부대상: §e") + t.getName());
    }

    private Player pickRandomOtherPlayer(Player self) {
        List<Player> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals(self)) continue;
            if (p.isDead()) continue;
            list.add(p);
        }
        if (list.isEmpty()) return null;
        return list.get(new Random().nextInt(list.size()));
    }

    private void applyStackBuff(Player p) {
        int s = stacks.getOrDefault(p.getUniqueId(), 0);

        // ✅ 최대체력 스택(NamespacedKey 기반 modifier)
        var attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            // 기존 modifier 제거(키로 식별)
            attr.getModifiers().stream()
                    .filter(m -> m.getKey().equals(hpKey))
                    .forEach(attr::removeModifier);

            double amount = HP_PER_STACK * s;
            if (amount > 0) {
                AttributeModifier mod = new AttributeModifier(
                        hpKey,
                        amount,
                        AttributeModifier.Operation.ADD_NUMBER
                );
                attr.addModifier(mod);
            }

            double max = p.getMaxHealth();
            if (p.getHealth() > max) p.setHealth(max);
        }

        // ✅ 힘 버프: 1.21에서는 STRENGTH 사용
        p.removePotionEffect(PotionEffectType.STRENGTH);

        if (s > 0) {
            int level = Math.min(s, MAX_STRENGTH_LEVEL); // 1~MAX
            int amp = level - 1; // amplifier: 0=I, 1=II ...
            p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, LONG_TICKS, amp, true, false, true));
        }
    }

    private void removeBuff(Player p) {
        var attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            attr.getModifiers().stream()
                    .filter(m -> m.getKey().equals(hpKey))
                    .forEach(attr::removeModifier);

            double max = p.getMaxHealth();
            if (p.getHealth() > max) p.setHealth(max);
        }

        p.removePotionEffect(PotionEffectType.STRENGTH);
    }
}