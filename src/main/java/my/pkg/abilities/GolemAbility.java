package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

public class GolemAbility implements Ability {

    private final JavaPlugin plugin;
    private final NamespacedKey attackSpeedKey;

    private static final double LAUNCH_Y = 1.15;
    private static final double LAUNCH_BACK = 0.25;

    private final NamespacedKey healthKey;
    private static final double BONUS_HEALTH = 4.0; // 2칸

    public GolemAbility(JavaPlugin plugin) {
        this.plugin = plugin;
        this.attackSpeedKey = new NamespacedKey(plugin, "Golem_slow_attack");
        this.healthKey = new NamespacedKey(plugin, "golem_bonus_health");
    }

    @Override
    public String id() {
        return "golem";
    }

    @Override
    public String name() {
        return "골렘";
    }

    @Override
    public int cooldownSeconds() {
        return 0;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        applyAttackSpeedPenalty(player);
        applyBonusHealth(player);

        player.sendMessage("§f[골렘] §7타격 시 적을 공중으로 띄웁니다.");
        player.sendMessage("§7최대체력이 2칸 증가하지만 기본 공격속도가 조금 느립니다.");
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        removeAttackSpeedPenalty(player);
        removeBonusHealth(player);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        player.sendMessage("§7[골렘] 이 능력은 패시브 능력입니다.");
        return false;
    }

    @Override
    public void onAttack(AbilitySystem system, EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        if (event.isCancelled()) return;

        Vector dir = victim.getLocation().toVector()
                .subtract(attacker.getLocation().toVector());

        if (dir.lengthSquared() > 0.001) {
            dir.normalize().multiply(LAUNCH_BACK);
        } else {
            dir = new Vector(0, 0, 0);
        }

        dir.setY(LAUNCH_Y);

        Vector finalDir = dir;

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!victim.isOnline() || victim.isDead()) return;

            victim.setFallDistance(0);
            victim.setVelocity(finalDir);

            victim.getWorld().playSound(
                    victim.getLocation(),
                    Sound.ENTITY_IRON_GOLEM_ATTACK,
                    0.8f,
                    1.2f
            );
        });
    }

    private void applyAttackSpeedPenalty(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.ATTACK_SPEED);
        if (attr == null) return;

        removeAttackSpeedPenalty(player);

        AttributeModifier modifier = new AttributeModifier(
                attackSpeedKey,
                -1.2,
                AttributeModifier.Operation.ADD_NUMBER
        );

        attr.addModifier(modifier);
    }

    private void applyBonusHealth(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;

        removeBonusHealth(player);

        AttributeModifier modifier = new AttributeModifier(
                healthKey,
                BONUS_HEALTH,
                AttributeModifier.Operation.ADD_NUMBER
        );

        attr.addModifier(modifier);

        // 증가한 체력만큼 현재 체력도 채워줌
        player.setHealth(Math.min(player.getHealth() + BONUS_HEALTH, attr.getValue()));
    }

    private void removeBonusHealth(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;

        attr.getModifiers().stream()
                .filter(mod -> mod.getKey().equals(healthKey))
                .findFirst()
                .ifPresent(attr::removeModifier);

        // 최대체력 줄었을 때 현재 체력이 초과하면 맞춰줌
        player.setHealth(Math.min(player.getHealth(), attr.getValue()));
    }

    private void removeAttackSpeedPenalty(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.ATTACK_SPEED);
        if (attr == null) return;

        attr.getModifiers().stream()
                .filter(mod -> mod.getKey().equals(attackSpeedKey))
                .findFirst()
                .ifPresent(attr::removeModifier);
    }
}