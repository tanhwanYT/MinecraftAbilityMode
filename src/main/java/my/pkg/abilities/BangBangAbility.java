package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class BangBangAbility implements Ability {

    private static final double MAX_HEALTH = 20.0;     // 10칸
    private static final int DURATION_TICKS = 20 * 8;  // 8초
    private static final double RADIUS = 6.0;          // 주변 플레이어 범위
    private static final int JUMP_AMPLIFIER = 1;       // 점프강화 II (0이면 I)

    // 현재 스킬이 켜져 있는 방방 플레이어들
    private final Set<UUID> activePlayers = new HashSet<>();

    @Override
    public String id() {
        return "bangbang";
    }

    @Override
    public String name() {
        return "방방";
    }

    @Override
    public int cooldownSeconds() {
        return 30; // 원하는 값으로 조절
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            attr.setBaseValue(MAX_HEALTH);
            if (player.getHealth() > MAX_HEALTH) {
                player.setHealth(MAX_HEALTH);
            }
        }

        player.sendMessage("§a방방 §7: 사용 시 8초 동안 자신과 주변 플레이어가 점프 강화를 얻습니다.");
        player.sendMessage("§7지속시간 동안 자신은 공중에 있으면 피해를 받지 않습니다.");
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            attr.setBaseValue(20.0);
        }

        if (player.getHealth() > 20.0) {
            player.setHealth(20.0);
        }

        activePlayers.remove(player.getUniqueId());
    }

    @Override
    public void onDamage(AbilitySystem system, EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!activePlayers.contains(player.getUniqueId())) return;
        if (!isAirborne(player)) return;

        event.setCancelled(true);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        UUID uuid = player.getUniqueId();

        if (activePlayers.contains(uuid)) {
            player.sendMessage("§c이미 방방 능력이 활성화 중입니다.");
            return false;
        }

        activePlayers.add(uuid);

        // 자기 자신
        applyJump(player);

        // 주변 플레이어
        for (Entity entity : player.getNearbyEntities(RADIUS, RADIUS, RADIUS)) {
            if (entity instanceof Player nearby) {
                if (nearby.getGameMode() == GameMode.SPECTATOR) continue;
                applyJump(nearby);
            }
        }

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.2f);
        player.sendMessage("§a[방방] §f8초 동안 점프 강화가 적용됩니다.");

        system.getPlugin().getServer().getScheduler().runTaskLater(system.getPlugin(), () -> {
            activePlayers.remove(uuid);

            if (player.isOnline()) {
                player.sendMessage("§c[방방] §f지속시간이 종료되었습니다.");
            }
        }, DURATION_TICKS);

        return true;
    }

    private void applyJump(Player player) {
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.JUMP_BOOST,
                DURATION_TICKS,
                JUMP_AMPLIFIER,
                false,
                true,
                true
        ));
    }

    private boolean isAirborne(Player player) {
        if (player.isOnGround()) return false;
        if (player.isInWater()) return false;
        if (player.isClimbing()) return false;
        if (player.isGliding()) return false;
        return true;
    }
}