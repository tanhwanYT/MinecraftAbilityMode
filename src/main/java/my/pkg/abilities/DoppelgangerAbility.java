package my.pkg.abilities;

import my.pkg.AbilitySystem;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import io.papermc.paper.datacomponent.item.ResolvableProfile;

import java.util.*;

public class DoppelgangerAbility implements Ability {

    private static final int COOLDOWN = 10;
    private static final int DURATION_TICKS = 20 * 3;
    private static final double MOVE_SPEED = 0.30;
    private static final double EXPLOSION_RADIUS = 3.5;
    private static final double MAX_DAMAGE = 12.0;

    private final Map<UUID, UUID> mannequinIds = new HashMap<>();
    private final Map<UUID, BukkitTask> followTasks = new HashMap<>();
    private final Set<UUID> activePlayers = new HashSet<>();

    private final Map<UUID, GearSnapshot> hiddenGear = new HashMap<>();

    @Override
    public String id() {
        return "doppelganger";
    }

    @Override
    public String name() {
        return "도플갱어";
    }

    @Override
    public int cooldownSeconds() {
        return COOLDOWN;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        // 능력 지급 시 장비를 건드리지 않음
        cleanupResidualOnly(player);

        player.sendMessage("§5[능력] §f당신의 능력은 §d도플갱어§f입니다.");
        player.sendMessage("§7네더스타 우클릭 시 분신 마네킹을 소환합니다.");
        player.sendMessage("§73초 뒤 분신이 폭발하며 주변에 피해를 줍니다.");
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        endSkill(player, false);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        UUID uuid = player.getUniqueId();

        if (activePlayers.contains(uuid)) {
            player.sendMessage("§c[도플갱어] 이미 분신이 활성화되어 있습니다.");
            return false;
        }

        JavaPlugin plugin = system.getPlugin();
        Location spawnLoc = player.getLocation().clone();

        Mannequin mannequin;
        try {
            mannequin = spawnMannequin(player, spawnLoc);
        } catch (Throwable t) {
            player.sendMessage("§c[도플갱어] 마네킹 소환에 실패했습니다.");
            t.printStackTrace();
            return false;
        }

        activePlayers.add(uuid);
        mannequinIds.put(uuid, mannequin.getUniqueId());

        applyHiddenState(player);

        player.sendMessage("§d[도플갱어] §f분신을 소환했습니다.");
        player.playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.1f);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    endSkill(player, false);
                    return;
                }

                Entity entity = Bukkit.getEntity(mannequin.getUniqueId());
                if (!(entity instanceof Mannequin liveMannequin) || !liveMannequin.isValid()) {
                    endSkill(player, false);
                    return;
                }

                Location playerLook = player.getLocation();

                // 바라보는 방향만 플레이어 따라감
                liveMannequin.setRotation(playerLook.getYaw(), playerLook.getPitch());

                // 수평 이동 방향
                Vector forward = playerLook.getDirection().clone().setY(0);
                if (forward.lengthSquared() > 0.0001) {
                    forward.normalize().multiply(MOVE_SPEED);
                } else {
                    forward = new Vector(0, 0, 0);
                }

                Vector currentVel = liveMannequin.getVelocity();

                // 중력은 유지하고, X/Z만 계속 밀어줌
                Vector nextVel = new Vector(
                        forward.getX(),
                        currentVel.getY(),
                        forward.getZ()
                );

                // 벽 충돌 체크
                Location horizontal = liveMannequin.getLocation().clone().add(forward);
                if (canMoveHorizontally(horizontal)) {
                    liveMannequin.setVelocity(nextVel);
                } else {
                    // 막히면 수평 이동만 멈춤, 중력은 유지
                    liveMannequin.setVelocity(new Vector(0, currentVel.getY(), 0));
                }

                tick++;
                if (tick >= DURATION_TICKS) {
                    endSkill(player, true);
                }
            }
        }, 0L, 1L);

        followTasks.put(uuid, task);
        return true;
    }

    @Override
    public void onAttack(AbilitySystem system, EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!activePlayers.contains(player.getUniqueId())) return;

        event.setCancelled(true);
        player.sendActionBar("§7분신 활성화 중에는 공격할 수 없습니다.");
    }

    @Override
    public void onDamage(AbilitySystem system, EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!activePlayers.contains(player.getUniqueId())) return;

        event.setCancelled(true);
    }

    private Mannequin spawnMannequin(Player owner, Location loc) {
        return owner.getWorld().spawn(loc, Mannequin.class, mannequin -> {
            mannequin.customName(Component.text(owner.getName()));
            mannequin.setCustomNameVisible(true);

            // NPC 문구 제거
            mannequin.setDescription(null);

            mannequin.setImmovable(false);
            mannequin.setGravity(true);
            mannequin.setNoPhysics(false);

            if (mannequin.getAttribute(Attribute.MAX_HEALTH) != null) {
                Objects.requireNonNull(mannequin.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(20.0);
                mannequin.setHealth(20.0);
            }

            try {
                ResolvableProfile profile = ResolvableProfile
                        .resolvableProfile()
                        .name(owner.getName())
                        .build();
                mannequin.setProfile(profile);
            } catch (Throwable t) {
                Bukkit.getLogger().warning("[Doppelganger] mannequin profile apply failed: " + t.getMessage());
            }

            copyArmorOnly(owner, mannequin);

            Location pLoc = owner.getLocation();
            mannequin.setRotation(pLoc.getYaw(), pLoc.getPitch());

            EntityEquipment me = mannequin.getEquipment();
            if (me != null) {
                me.setItemInMainHand(null);
                me.setItemInOffHand(null);
            }
        });
    }

    private void copyArmorOnly(Player player, Mannequin mannequin) {
        EntityEquipment pe = player.getEquipment();
        EntityEquipment me = mannequin.getEquipment();
        if (pe == null || me == null) return;

        me.setHelmet(cloneOrNull(pe.getHelmet()));
        me.setChestplate(cloneOrNull(pe.getChestplate()));
        me.setLeggings(cloneOrNull(pe.getLeggings()));
        me.setBoots(cloneOrNull(pe.getBoots()));
        me.setItemInMainHand(null);
        me.setItemInOffHand(null);
    }

    private void applyHiddenState(Player player) {
        UUID uuid = player.getUniqueId();
        if (hiddenGear.containsKey(uuid)) return;

        EntityEquipment eq = player.getEquipment();
        if (eq != null) {
            hiddenGear.put(uuid, new GearSnapshot(
                    cloneOrNull(eq.getHelmet()),
                    cloneOrNull(eq.getChestplate()),
                    cloneOrNull(eq.getLeggings()),
                    cloneOrNull(eq.getBoots()),
                    cloneOrNull(eq.getItemInMainHand()),
                    cloneOrNull(eq.getItemInOffHand())
            ));

            eq.setHelmet(null);
            eq.setChestplate(null);
            eq.setLeggings(null);
            eq.setBoots(null);
            eq.setItemInMainHand(null);
            eq.setItemInOffHand(null);
        }

        player.addPotionEffect(new PotionEffect(
                PotionEffectType.INVISIBILITY,
                DURATION_TICKS + 10,
                0,
                false,
                false,
                false
        ));

        player.getWorld().spawnParticle(
                Particle.SMOKE,
                player.getLocation().add(0, 1, 0),
                20, 0.3, 0.5, 0.3, 0.02
        );
    }

    private boolean canMoveHorizontally(Location loc) {
        World world = loc.getWorld();
        if (world == null) return false;

        Location feet = loc.clone();
        Location body = loc.clone().add(0, 1, 0);

        return feet.getBlock().isPassable() && body.getBlock().isPassable();
    }

    private void removeHiddenState(Player player) {
        UUID uuid = player.getUniqueId();
        player.removePotionEffect(PotionEffectType.INVISIBILITY);

        GearSnapshot snap = hiddenGear.remove(uuid);
        if (snap == null) return;

        EntityEquipment eq = player.getEquipment();
        if (eq == null) return;

        eq.setHelmet(snap.helmet);
        eq.setChestplate(snap.chestplate);
        eq.setLeggings(snap.leggings);
        eq.setBoots(snap.boots);
        eq.setItemInMainHand(snap.mainHand);
        eq.setItemInOffHand(snap.offHand);
    }

    private void endSkill(Player owner, boolean explode) {
        UUID uuid = owner.getUniqueId();

        BukkitTask task = followTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }

        Location boomLoc = null;
        UUID mannequinId = mannequinIds.remove(uuid);
        if (mannequinId != null) {
            Entity entity = Bukkit.getEntity(mannequinId);
            if (entity != null && entity.isValid()) {
                boomLoc = entity.getLocation().clone();
                entity.remove();
            }
        }

        activePlayers.remove(uuid);
        removeHiddenState(owner);

        if (explode && boomLoc != null) {
            doExplosionDamage(owner, boomLoc);
        }
    }

    private void cleanupResidualOnly(Player player) {
        UUID uuid = player.getUniqueId();

        BukkitTask task = followTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }

        UUID mannequinId = mannequinIds.remove(uuid);
        if (mannequinId != null) {
            Entity entity = Bukkit.getEntity(mannequinId);
            if (entity != null) {
                entity.remove();
            }
        }

        activePlayers.remove(uuid);

        // 숨김 상태가 실제로 걸려 있었을 때만 복구
        if (hiddenGear.containsKey(uuid)) {
            removeHiddenState(player);
        }
    }

    private void doExplosionDamage(Player owner, Location center) {
        World world = center.getWorld();
        if (world == null) return;

        world.spawnParticle(Particle.EXPLOSION, center, 1);
        world.spawnParticle(Particle.SMOKE, center, 25, 0.35, 0.2, 0.35, 0.03);
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.1f);

        for (LivingEntity target : center.getNearbyLivingEntities(EXPLOSION_RADIUS)) {
            if (target.equals(owner)) continue;
            if (!(target instanceof Player victim)) continue;
            if (victim.getGameMode() == GameMode.SPECTATOR) continue;

            double dist = victim.getLocation().distance(center);
            if (dist > EXPLOSION_RADIUS) continue;

            double ratio = 1.0 - (dist / EXPLOSION_RADIUS);
            double damage = Math.max(1.0, MAX_DAMAGE * ratio);

            victim.damage(damage, owner);

            Vector kb = victim.getLocation().toVector().subtract(center.toVector());
            if (kb.lengthSquared() > 0.0001) {
                kb.normalize().multiply(0.65).setY(0.28);
                victim.setVelocity(victim.getVelocity().add(kb));
            }
        }
    }

    private ItemStack cloneOrNull(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private static class GearSnapshot {
        private final ItemStack helmet;
        private final ItemStack chestplate;
        private final ItemStack leggings;
        private final ItemStack boots;
        private final ItemStack mainHand;
        private final ItemStack offHand;

        private GearSnapshot(ItemStack helmet, ItemStack chestplate, ItemStack leggings,
                             ItemStack boots, ItemStack mainHand, ItemStack offHand) {
            this.helmet = helmet;
            this.chestplate = chestplate;
            this.leggings = leggings;
            this.boots = boots;
            this.mainHand = mainHand;
            this.offHand = offHand;
        }
    }
}