package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GlowAbility implements Ability {

    private static final int TARGET_GLOW_TICKS = 20 * 999;        // 맞춘 상대 발광 999초

    private static final int ON_TICKS = 10;   // 0.5초 ON
    private static final int OFF_TICKS = 10;  // 0.5초 OFF
    private static final Map<UUID, BukkitTask> blinkTasks = new ConcurrentHashMap<>();
    @Override
    public String id() { return "glow"; }

    @Override
    public String name() { return "라이징스타"; }

    @Override
    public int cooldownSeconds() { return 0; } // 우클릭 액티브가 없어서 의미는 덜함(그래도 시스템상 필요하면 유지)

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("라이징스타 : 당신은 항상 빛납니다. 대신 공격한 상대도 죽을때까지 빛나게 합니다.");
        startBlink(system, player);
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        stopBlink(player);
        player.removePotionEffect(PotionEffectType.GLOWING);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        player.sendMessage("§7[라이징스타]§f 패시브 능력입니다.");
        return false;
    }

    @Override
    public void onAttack(AbilitySystem system, EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof org.bukkit.entity.LivingEntity victim)) return;

        victim.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, TARGET_GLOW_TICKS, 0, true, false, true));

        // 소리는 맞은 엔티티 위치에서
        victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.4f);

        attacker.sendActionBar("§e[라이징스타] §f대상이 빛납니다!");
    }

    private void startBlink(AbilitySystem system, Player player) {
        UUID id = player.getUniqueId();

        stopBlink(player); // 혹시 중복 방지

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(system.getPlugin(), new Runnable() {

            boolean glowing = false;
            int timer = 0;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    stopBlink(player);
                    return;
                }

                timer++;

                if (glowing && timer >= ON_TICKS) {
                    player.removePotionEffect(PotionEffectType.GLOWING);
                    glowing = false;
                    timer = 0;
                }
                else if (!glowing && timer >= OFF_TICKS) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, ON_TICKS + 2, 0, true, false, true));
                    glowing = true;
                    timer = 0;
                }
            }
        }, 0L, 1L);

        blinkTasks.put(id, task);
    }

    private void stopBlink(Player player) {
        UUID id = player.getUniqueId();
        BukkitTask task = blinkTasks.remove(id);
        if (task != null) task.cancel();
    }
}
