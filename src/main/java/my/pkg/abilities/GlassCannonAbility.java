package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GlassCannonAbility implements Ability {

    // 단계별 설정
    private static final double[] DAMAGE_MULTIPLIERS = {1.1, 1.2, 1.3, 1.4, 1.5};
    private static final double[] HEALTH_RATIOS      = {0.9, 0.8, 0.7, 0.6, 0.5};

    private static final int DEFAULT_TIER = 1; // 0=1단계, 1=2단계(기본 1.2배 / 0.8배)

    private static final Map<UUID, Double> originalHealth = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> currentTier = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> holders = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "glasscannon";
    }

    @Override
    public String name() {
        return "유리대포";
    }

    @Override
    public int cooldownSeconds() {
        return 0;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;

        originalHealth.put(player.getUniqueId(), attr.getBaseValue());
        currentTier.put(player.getUniqueId(), DEFAULT_TIER);
        holders.put(player.getUniqueId(), true);

        applyTier(player);

        player.sendMessage("§c[유리대포] §f체력이 감소하지만 공격력이 증가합니다.");
        player.sendMessage("§7- 능력 사용: 단계 상승");
        player.sendMessage("§7- 쉬프트 + 능력 사용: 단계 하강");
        sendTierInfo(player);
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            Double original = originalHealth.remove(player.getUniqueId());
            if (original != null) {
                attr.setBaseValue(original);
                player.setHealth(Math.min(original, player.getHealth()));
            }
        }

        currentTier.remove(player.getUniqueId());
        holders.remove(player.getUniqueId());
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        if (!holders.containsKey(player.getUniqueId())) return false;

        int tier = currentTier.getOrDefault(player.getUniqueId(), DEFAULT_TIER);

        if (player.isSneaking()) {
            tier--;
            if (tier < 0) tier = DAMAGE_MULTIPLIERS.length - 1;
        } else {
            tier++;
            if (tier >= DAMAGE_MULTIPLIERS.length) tier = 0;
        }

        currentTier.put(player.getUniqueId(), tier);
        applyTier(player);

        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
        sendTierInfo(player);
        return false; // 조절형 패시브니까 쿨 소모 없이
    }

    @Override
    public void onAttack(AbilitySystem system, EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!holders.containsKey(attacker.getUniqueId())) return;

        int tier = currentTier.getOrDefault(attacker.getUniqueId(), DEFAULT_TIER);
        double multiplier = DAMAGE_MULTIPLIERS[tier];

        event.setDamage(event.getDamage() * multiplier);
    }

    private void applyTier(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;

        UUID id = player.getUniqueId();
        Double original = originalHealth.get(id);
        if (original == null) return;

        double oldMax = attr.getBaseValue();
        double currentHealth = player.getHealth();

        double healthPercent = oldMax > 0.0 ? (currentHealth / oldMax) : 1.0;
        healthPercent = Math.max(0.0, Math.min(1.0, healthPercent));

        int tier = currentTier.getOrDefault(id, DEFAULT_TIER);
        double newMax = original * HEALTH_RATIOS[tier];

        attr.setBaseValue(newMax);

        double newHealth = Math.max(1.0, newMax * healthPercent);
        player.setHealth(Math.min(newMax, newHealth));
    }

    private void sendTierInfo(Player player) {
        int tier = currentTier.getOrDefault(player.getUniqueId(), DEFAULT_TIER);
        double damage = DAMAGE_MULTIPLIERS[tier];
        double health = HEALTH_RATIOS[tier];

        player.sendMessage("§c[유리대포] §f현재 설정: §e" + (tier + 1) + "단계");
        player.sendMessage("§7- 공격력: §a" + damage + "배");
        player.sendMessage("§7- 최대체력: §c" + health + "배");
    }
}