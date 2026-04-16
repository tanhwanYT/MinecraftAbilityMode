package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vindicator;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AhabAbility implements Ability, Listener {

    private static final int COOLDOWN_SECONDS = 20;

    private static final int SUMMON_COUNT = 2;
    private static final double SUMMON_OFFSET = 1.5;

    private static final double VINDICATOR_MAX_HEALTH = 12.0; // 6칸
    private static final double BLAME_DAMAGE = 6.0;           // 일반 피해 3칸
    private static final double BLAME_RANGE = 15.0;
    private static final String BLAME_PREFIX = "네탓이군! ";

    private final AbilitySystem abilitySystem;
    private final JavaPlugin plugin;

    private static final long BLAME_COOLDOWN_MS = 40_000; // 8초
    private final Map<UUID, Long> blameCooldowns = new ConcurrentHashMap<>();

    public AhabAbility(JavaPlugin plugin, AbilitySystem abilitySystem) {
        this.plugin = plugin;
        this.abilitySystem = abilitySystem;
    }

    // 소환수 UUID -> 주인 UUID
    private final Map<UUID, UUID> summonOwnerMap = new ConcurrentHashMap<>();
    // 주인 UUID -> 소환수 UUID들
    private final Map<UUID, Set<UUID>> ownerSummons = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "ahab";
    }

    @Override
    public String name() {
        return "에이해브";
    }

    @Override
    public int cooldownSeconds() {
        return COOLDOWN_SECONDS;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("§6에이해브 §f: 능력 사용 시 자신을 공격하지 않는 변명자 2마리를 소환합니다.");
        player.sendMessage("§7채팅으로 §e네탓이군! 닉네임§7 입력 시 대상에게 피해를 줍니다. (대상이 5칸 이내일때만 발동)");
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        removeAllSummons(player.getUniqueId());
        blameCooldowns.remove(player.getUniqueId());
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.sendMessage("§c[에이해브] 관전 상태에서는 사용할 수 없습니다.");
            return false;
        }

        removeAllSummons(player.getUniqueId());

        Location base = player.getLocation();
        for (int i = 0; i < SUMMON_COUNT; i++) {
            double angle = Math.toRadians((360.0 / SUMMON_COUNT) * i);
            double x = Math.cos(angle) * SUMMON_OFFSET;
            double z = Math.sin(angle) * SUMMON_OFFSET;

            Location spawnLoc = base.clone().add(x, 0, z);
            Vindicator vindicator = player.getWorld().spawn(spawnLoc, Vindicator.class, mob -> {
                mob.setCustomName("§c피쿼드호 선원");
                mob.setCustomNameVisible(false);
                mob.setRemoveWhenFarAway(false);
                mob.setPersistent(true);

                AttributeInstance attr = mob.getAttribute(Attribute.MAX_HEALTH);
                if (attr != null) {
                    attr.setBaseValue(VINDICATOR_MAX_HEALTH);
                }
                mob.setHealth(VINDICATOR_MAX_HEALTH);
            });

            summonOwnerMap.put(vindicator.getUniqueId(), player.getUniqueId());
            ownerSummons.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet())
                    .add(vindicator.getUniqueId());
        }

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1.0f, 0.9f);
        player.sendMessage("§6[에이해브] §f변명자 2마리를 소환했습니다.");
        return true;
    }

    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Vindicator vindicator)) return;
        if (!(event.getTarget() instanceof Player target)) return;

        UUID ownerId = summonOwnerMap.get(vindicator.getUniqueId());
        if (ownerId == null) return;

        if (target.getUniqueId().equals(ownerId)) {
            event.setCancelled(true);
            event.setTarget(null);
        }
    }

    @EventHandler
    public void onFriendlyDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Vindicator vindicator)) return;
        if (!(event.getEntity() instanceof Player player)) return;

        UUID ownerId = summonOwnerMap.get(vindicator.getUniqueId());
        if (ownerId == null) return;

        if (player.getUniqueId().equals(ownerId)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSummonDeath(EntityDeathEvent event) {
        UUID entityId = event.getEntity().getUniqueId();
        UUID ownerId = summonOwnerMap.remove(entityId);
        if (ownerId == null) return;

        event.getDrops().clear();
        event.setDroppedExp(0);

        Set<UUID> summons = ownerSummons.get(ownerId);
        if (summons != null) {
            summons.remove(entityId);
            if (summons.isEmpty()) {
                ownerSummons.remove(ownerId);
            }
        }
    }

    @EventHandler
    public void onSummonAttackDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Vindicator vindicator)) return;

        UUID ownerId = summonOwnerMap.get(vindicator.getUniqueId());
        if (ownerId == null) return; // 내가 소환한 변명자만 적용

        event.setDamage(event.getDamage() * 0.7); // 30% 감소
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeAllSummons(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player caster = event.getPlayer();
        String message = event.getMessage();

        if (!message.startsWith(BLAME_PREFIX)) return;

        Ability ability = abilitySystem.getState(caster).getAbility();
        if (ability != this) return;

        event.setCancelled(true);

        long now = System.currentTimeMillis();
        long lastUse = blameCooldowns.getOrDefault(caster.getUniqueId(), 0L);

        if (now - lastUse < BLAME_COOLDOWN_MS) {
            long left = (BLAME_COOLDOWN_MS - (now - lastUse) + 999) / 1000;
            caster.sendMessage("§c[에이해브] 아직 쿨타임입니다. (" + left + "초 남음)");
            return;
        }

        String targetName = message.substring(BLAME_PREFIX.length()).trim();
        if (targetName.isEmpty()) {
            caster.sendMessage("§c[에이해브] 닉네임을 입력하세요. 예: 네탓이군! Steve");
            return;
        }

        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline() || target.isDead()) {
            caster.sendMessage("§c[에이해브] 대상을 찾을 수 없습니다.");
            return;
        }

        if (target.equals(caster)) {
            caster.sendMessage("§c[에이해브] 자기 자신을 탓할 수는 없습니다.");
            return;
        }

        if (target.getGameMode() == GameMode.SPECTATOR) {
            caster.sendMessage("§c[에이해브] 그 대상에게는 사용할 수 없습니다.");
            return;
        }

        if (!caster.getWorld().equals(target.getWorld())) {
            caster.sendMessage("§c[에이해브] 같은 월드의 대상만 지정할 수 있습니다.");
            return;
        }

        double distSq = caster.getLocation().distanceSquared(target.getLocation());
        if (distSq > BLAME_RANGE * BLAME_RANGE) {
            caster.sendMessage("§c[에이해브] 대상이 너무 멉니다. (" + (int) BLAME_RANGE + "칸 이내)");
            return;
        }

        blameCooldowns.put(caster.getUniqueId(), now);

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!caster.isOnline() || !target.isOnline() || target.isDead()) return;
            if (!caster.getWorld().equals(target.getWorld())) return;
            if (caster.getLocation().distanceSquared(target.getLocation()) > BLAME_RANGE * BLAME_RANGE) return;

            target.damage(BLAME_DAMAGE, caster);

            caster.sendMessage("§6[에이해브] §f네 탓이군! → §c" + target.getName());
            target.sendMessage("§c[에이해브] §f" + caster.getName() + "의 비난이 당신을 꿰뚫었습니다.");

            caster.getWorld().playSound(caster.getLocation(), Sound.ENTITY_VINDICATOR_AMBIENT, 0.9f, 0.9f);
            target.getWorld().playSound(target.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 0.8f);
        });
    }

    private void removeAllSummons(UUID ownerId) {
        Set<UUID> summons = ownerSummons.remove(ownerId);
        if (summons == null) return;

        for (UUID summonId : new HashSet<>(summons)) {
            summonOwnerMap.remove(summonId);
            Entity entity = Bukkit.getEntity(summonId);
            if (entity != null && entity.isValid()) {
                entity.remove();
            }
        }
    }
}