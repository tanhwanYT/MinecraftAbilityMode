package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CrabAbility implements Ability, Listener {

    private static boolean listenerRegistered = false;

    private final Set<UUID> holders = ConcurrentHashMap.newKeySet();

    private static final double SIDE_SPEED_MULTIPLIER = 1.08;

    @Override
    public String id() {
        return "crab";
    }

    @Override
    public String name() {
        return "게";
    }

    @Override
    public int cooldownSeconds() {
        return 0;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        holders.add(player.getUniqueId());

        if (!listenerRegistered) {
            Bukkit.getPluginManager().registerEvents(this, system.getPlugin());
            listenerRegistered = true;
        }

        applyCrabBuff(player);

        player.sendMessage("§b[게] §f당신은 게입니다.");
        player.sendMessage("§7- 영구 신속 II, 성급함 I, 저항 I을 받습니다.");
        player.sendMessage("§7- 앞/뒤 이동은 약하게 제한되고, 좌우 이동에 특화됩니다.");
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        holders.remove(player.getUniqueId());
        player.removePotionEffect(PotionEffectType.SPEED);
        player.removePotionEffect(PotionEffectType.HASTE);
        player.removePotionEffect(PotionEffectType.RESISTANCE);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        player.sendMessage("게는 옆으로 걷습니다.");
        player.playSound(player.getLocation(), Sound.ENTITY_TURTLE_SHAMBLE, 1f, 1.4f);
        return false;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (!holders.contains(player.getUniqueId())) return;
        if (player.getGameMode() != GameMode.SURVIVAL) return;
        if (event.getTo() == null) return;

        applyCrabBuff(player);

        Vector move = event.getTo().toVector().subtract(event.getFrom().toVector());
        move.setY(0);

        if (move.lengthSquared() < 0.00003) return;

        Vector forward = player.getLocation().getDirection().clone().setY(0);
        if (forward.lengthSquared() < 0.0001) return;

        forward.normalize();

        double forwardAmount = move.dot(forward);

        // 너무 작은 앞/뒤 흔들림은 무시해서 덜 끊기게 함
        if (Math.abs(forwardAmount) < 0.015) return;

        Vector blockedForward = forward.clone().multiply(forwardAmount);

        Vector allowedSideMove = move.clone().subtract(blockedForward);

        // 보정을 100% 하지 않고 85%만 적용해서 덜 딱딱하게 만듦
        Vector softenedMove = move.clone().multiply(0.15).add(allowedSideMove.multiply(0.85));

        var fixed = event.getFrom().clone().add(softenedMove);
        fixed.setY(event.getTo().getY());
        fixed.setYaw(event.getTo().getYaw());
        fixed.setPitch(event.getTo().getPitch());

        event.setTo(fixed);

        if (Math.random() < 0.08) {
            player.getWorld().spawnParticle(
                    Particle.BUBBLE_POP,
                    player.getLocation().add(0, 0.2, 0),
                    2,
                    0.25, 0.1, 0.25,
                    0.01
            );
        }
    }

    private void applyCrabBuff(Player player) {
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.SPEED,
                PotionEffect.INFINITE_DURATION,
                1,
                false,
                false,
                false
        ));

        player.addPotionEffect(new PotionEffect(
                PotionEffectType.HASTE,
                PotionEffect.INFINITE_DURATION,
                0,
                false,
                false,
                false
        ));

        player.addPotionEffect(new PotionEffect(
                PotionEffectType.RESISTANCE,
                PotionEffect.INFINITE_DURATION,
                0,
                false,
                false,
                false
        ));
    }
}