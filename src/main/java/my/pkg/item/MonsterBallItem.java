package my.pkg.item;

import my.pkg.SupplyItem;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public class MonsterBallItem implements SupplyItem {

    private final NamespacedKey itemIdKey;
    private final NamespacedKey projectileKey;

    private final Map<UUID, Location> trapped = new HashMap<>();
    private final Map<UUID, List<Entity>> displays = new HashMap<>();

    private static final int TRAP_TICKS = 20 * 5;

    public MonsterBallItem(JavaPlugin plugin, NamespacedKey itemIdKey) {
        this.itemIdKey = itemIdKey;
        this.projectileKey = new NamespacedKey(plugin, "monster_ball_projectile");
    }

    @Override
    public String id() {
        return "monster_ball";
    }

    @Override
    public ItemStack create(JavaPlugin plugin) {
        ItemStack item = new ItemStack(Material.SNOWBALL, 1);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName("§c몬스터볼");
            meta.setLore(List.of(
                    "§7상대에게 던져 적중시키면",
                    "§75초 동안 몬스터볼 안에 가둡니다.",
                    "§8이동불가 / 투명 / 무적"
            ));
            meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, id());
            item.setItemMeta(meta);
        }

        return item;
    }

    @Override
    public void onRightClick(JavaPlugin plugin, Player p, PlayerInteractEvent e) {
        e.setCancelled(true);

        ItemStack hand = p.getInventory().getItemInMainHand();

        Snowball ball = p.launchProjectile(Snowball.class);
        ball.setVelocity(p.getEyeLocation().getDirection().multiply(1.5));
        ball.setShooter(p);
        ball.getPersistentDataContainer().set(projectileKey, PersistentDataType.BYTE, (byte) 1);

        ItemStack visual = new ItemStack(Material.SNOWBALL);
        ItemMeta meta = visual.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§c몬스터볼");
            visual.setItemMeta(meta);
        }
        ball.setItem(visual);

        hand.setAmount(hand.getAmount() - 1);

        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1.0f, 0.8f);
    }

    @Override
    public void onProjectileHit(JavaPlugin plugin, ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Snowball ball)) return;

        Byte tag = ball.getPersistentDataContainer().get(projectileKey, PersistentDataType.BYTE);
        if (tag == null || tag != (byte) 1) return;

        if (!(e.getHitEntity() instanceof Player target)) return;
        if (trapped.containsKey(target.getUniqueId())) return;

        trap(plugin, target);
    }

    private void trap(JavaPlugin plugin, Player target) {
        UUID uuid = target.getUniqueId();
        Location center = target.getLocation().clone();

        trapped.put(uuid, center);

        target.setInvulnerable(true);
        target.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, TRAP_TICKS, 0, false, false, false));
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, TRAP_TICKS, 255, false, false, false));
        target.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, TRAP_TICKS, 128, false, false, false));

        target.getWorld().playSound(center, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.4f);
        target.sendMessage("§c몬스터볼에 갇혔습니다! §75초 동안 행동이 제한됩니다.");

        List<Entity> model = spawnMonsterBallDisplay(center);
        displays.put(uuid, model);

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                Player p = Bukkit.getPlayer(uuid);

                if (p == null || !p.isOnline()) {
                    release(uuid);
                    cancel();
                    return;
                }

                if (ticks >= TRAP_TICKS) {
                    release(uuid);
                    cancel();
                    return;
                }

                Location lock = trapped.get(uuid);
                if (lock != null) {
                    p.teleport(lock);
                    p.getWorld().spawnParticle(
                            Particle.END_ROD,
                            lock.clone().add(0, 1.0, 0),
                            4,
                            0.35, 0.35, 0.35,
                            0.01
                    );
                }

                ticks += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private List<Entity> spawnMonsterBallDisplay(Location center) {
        World world = center.getWorld();
        List<Entity> list = new ArrayList<>();

        Location base = center.clone().add(-0.75, 0.1, -0.75);

        BlockDisplay white = spawnDisplay(world, base.clone().add(0, 0.0, 0), Material.WHITE_CONCRETE, 1.5f, 0.75f, 1.5f);
        BlockDisplay black = spawnDisplay(world, base.clone().add(0, 0.75, 0), Material.BLACK_CONCRETE, 1.5f, 0.18f, 1.5f);
        BlockDisplay red = spawnDisplay(world, base.clone().add(0, 0.93, 0), Material.RED_CONCRETE, 1.5f, 0.75f, 1.5f);
        BlockDisplay button = spawnDisplay(world, center.clone().add(-0.18, 0.78, -0.95), Material.WHITE_CONCRETE, 0.36f, 0.36f, 0.12f);

        list.add(white);
        list.add(black);
        list.add(red);
        list.add(button);

        return list;
    }

    private BlockDisplay spawnDisplay(World world, Location loc, Material mat, float sx, float sy, float sz) {
        BlockDisplay display = world.spawn(loc, BlockDisplay.class);
        BlockData data = Bukkit.createBlockData(mat);
        display.setBlock(data);
        display.setTransformation(new Transformation(
                new Vector3f(0, 0, 0),
                new Quaternionf(),
                new Vector3f(sx, sy, sz),
                new Quaternionf()
        ));
        display.setBrightness(new Display.Brightness(15, 15));
        return display;
    }

    private void release(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            p.setInvulnerable(false);
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
            p.removePotionEffect(PotionEffectType.SLOWNESS);
            p.removePotionEffect(PotionEffectType.JUMP_BOOST);
            p.playSound(p.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.0f, 1.2f);
            p.sendMessage("§a몬스터볼에서 풀려났습니다!");
        }

        trapped.remove(uuid);

        List<Entity> list = displays.remove(uuid);
        if (list != null) {
            for (Entity entity : list) {
                if (entity != null && !entity.isDead()) entity.remove();
            }
        }
    }

    @Override
    public void onPlayerMove(JavaPlugin plugin, PlayerMoveEvent e) {
        Location lock = trapped.get(e.getPlayer().getUniqueId());
        if (lock == null) return;

        if (e.getTo() == null) return;

        if (e.getTo().distanceSquared(lock) > 0.05) {
            e.setTo(lock);
        }
    }
}