package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class GamblerAbility implements Ability, org.bukkit.event.Listener {

    private static final int START_SURVIVAL_CHANCE = 100;
    private static final int SURVIVAL_DECREASE_PER_HIT = 1;
    private static final int MIN_SURVIVAL_CHANCE = 0;

    private static boolean listenerRegistered = false;

    private final Map<UUID, Integer> survivalChance = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> actionbarTasks = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "gambler";
    }

    @Override
    public String name() {
        return "도박꾼";
    }

    @Override
    public int cooldownSeconds() {
        return 0;
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        player.sendMessage("§d[도박꾼] 패시브 능력입니다.");
        return false;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        resetSurvivalChance(player);
        startActionbar(system, player);

        player.sendMessage("§d도박꾼 §f: 맨손 공격 시 무조건 3~7의 랜덤 고정 피해를 줍니다.");
        player.sendMessage("§7대신 공격할 때마다 생존 확률이 줄어듭니다.");
        player.sendMessage("§7생존 확률 판정에 실패하면 즉사합니다.");

        if (!listenerRegistered) {
            system.getPlugin().getServer().getPluginManager().registerEvents(this, system.getPlugin());
            listenerRegistered = true;
        }
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        resetSurvivalChance(player);
        stopActionbar(player);
        player.sendActionBar("");
    }

    @Override
    public void onAttack(AbilitySystem system, EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        if (attacker.getInventory().getItemInMainHand().getType() != Material.AIR) return;

        UUID uuid = attacker.getUniqueId();
        int currentChance = survivalChance.getOrDefault(uuid, START_SURVIVAL_CHANCE);

        int dmg = ThreadLocalRandom.current().nextInt(1, 6); // 1~5

        int roll = ThreadLocalRandom.current().nextInt(1, 101);
        boolean survived = roll <= currentChance;

        // 기본 타격 판정/넉백은 살리고 실제 피해만 0으로
        event.setDamage(0.0);

        if (!survived) {
            Bukkit.getScheduler().runTask(system.getPlugin(), () -> {
                if (!attacker.isOnline() || attacker.isDead()) return;

                Bukkit.broadcastMessage(
                        "§d[도박꾼] §f" + attacker.getName()
                                + "님이 생존 확률 §c" + currentChance + "%§f에서 터졌습니다. ㅋㅋ"
                );

                attacker.setHealth(0.0);
            });

            attacker.sendMessage("§c[도박꾼] 생존 실패! 확률이 당신을 버렸습니다!");
            return;
        }

        Bukkit.getScheduler().runTask(system.getPlugin(), () -> {
            if (!target.isValid() || target.isDead()) return;
            applyFixedDamage(attacker, target, dmg);
        });

        int nextChance = Math.max(MIN_SURVIVAL_CHANCE, currentChance - SURVIVAL_DECREASE_PER_HIT);
        survivalChance.put(uuid, nextChance);

        attacker.sendMessage("§a[도박꾼] 적중! 상대에게 " + dmg + " 고정 피해! §7(생존 확률: " + nextChance + "%)");
    }

    @org.bukkit.event.EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!survivalChance.containsKey(player.getUniqueId())) return;
        resetSurvivalChance(player);
    }

    private void applyFixedDamage(Player attacker, LivingEntity target, double damage) {
        double remain = damage;

        // 흡수체력 먼저 제거
        double absorption = target.getAbsorptionAmount();
        if (absorption > 0) {
            double used = Math.min(absorption, remain);
            target.setAbsorptionAmount(absorption - used);
            remain -= used;
        }

        if (remain <= 0) return;

        double newHealth = Math.max(0.0, target.getHealth() - remain);
        target.setHealth(newHealth);

        if (target instanceof Player playerTarget) {
            playerTarget.sendMessage("§c[도박꾼] " + attacker.getName() + "에게 고정 피해 " + (int) damage + "를 받았습니다!");
        }
    }

    private void startActionbar(AbilitySystem system, Player player) {
        stopActionbar(player);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(system.getPlugin(), () -> {
            if (!player.isOnline()) return;

            int chance = survivalChance.getOrDefault(player.getUniqueId(), START_SURVIVAL_CHANCE);
            player.sendActionBar("§7[§d도박꾼§7] §f생존 확률 §a" + chance + "%");
        }, 0L, 10L);

        actionbarTasks.put(player.getUniqueId(), task);
    }

    private void stopActionbar(Player player) {
        BukkitTask old = actionbarTasks.remove(player.getUniqueId());
        if (old != null) {
            old.cancel();
        }
    }

    private void resetSurvivalChance(Player player) {
        survivalChance.put(player.getUniqueId(), START_SURVIVAL_CHANCE);
    }
}