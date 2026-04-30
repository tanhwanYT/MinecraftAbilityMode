package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

public class SpellbookAbility implements Ability, Listener {

    private final JavaPlugin plugin;
    private final NamespacedKey spellKey;

    private static final int COOLDOWN = 25;
    private static final double TARGET_RANGE = 16.0;

    private final Set<UUID> holders = new HashSet<>();
    private final Map<UUID, Spell> selectedSpell = new HashMap<>();

    public SpellbookAbility(JavaPlugin plugin) {
        this.plugin = plugin;
        this.spellKey = new NamespacedKey(plugin, "spellbook_spell");
    }

    @Override
    public String id() {
        return "spellbook";
    }

    @Override
    public String name() {
        return "봉풀주";
    }

    @Override
    public int cooldownSeconds() {
        return COOLDOWN;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        holders.add(player.getUniqueId());
        selectedSpell.put(player.getUniqueId(), Spell.FLASH);

        player.sendMessage("§d[봉풀주] §f우클릭으로 스펠 사용, 좌클릭으로 스펠 변경 UI를 엽니다.");
        player.sendMessage("§7기본 스펠: §e점멸");
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        holders.remove(player.getUniqueId());
        selectedSpell.remove(player.getUniqueId());
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        Spell spell = selectedSpell.getOrDefault(player.getUniqueId(), Spell.FLASH);

        switch (spell) {
            case FLASH -> {
                return useFlash(player);
            }
            case GHOST -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 8, 2, false, true));
                player.playSound(player.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1f, 1.4f);
                player.sendMessage("§b[봉풀주] §f유체화를 사용했습니다.");
                return true;
            }
            case HEAL -> {
                double newHealth = Math.min(player.getHealth() + 8.0, player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
                player.setHealth(newHealth);
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 4, 0, false, true));
                player.getWorld().spawnParticle(Particle.HEART, player.getLocation().add(0, 1.2, 0), 12, 0.5, 0.5, 0.5, 0.02);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.6f);
                player.sendMessage("§a[봉풀주] §f회복을 사용했습니다.");
                return true;
            }
            case BARRIER -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 6, 2, false, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20 * 3, 0, false, true));
                player.getWorld().spawnParticle(Particle.ENCHANTED_HIT, player.getLocation().add(0, 1, 0), 25, 0.6, 0.8, 0.6, 0.03);
                player.playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1.2f);
                player.sendMessage("§e[봉풀주] §f방어막을 사용했습니다.");
                return true;
            }
            case EXHAUST -> {
                Player target = getTargetPlayer(player);
                if (target == null) {
                    player.sendMessage("§c[봉풀주] 탈진을 사용할 대상이 없습니다.");
                    return false;
                }

                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 5, 2, false, true));
                target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 20 * 5, 1, false, true));
                target.getWorld().spawnParticle(Particle.SMOKE, target.getLocation().add(0, 1, 0), 25, 0.4, 0.5, 0.4, 0.03);
                target.playSound(target.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.8f, 1.5f);
                player.sendMessage("§7[봉풀주] §f" + target.getName() + "에게 탈진");
                return true;
            }
            case IGNITE -> {
                Player target = getTargetPlayer(player);
                if (target == null) {
                    player.sendMessage("§c[봉풀주] 점화를 사용할 대상이 없습니다.");
                    return false;
                }

                target.setFireTicks(20 * 5);
                target.damage(3.0, player);
                target.getWorld().spawnParticle(Particle.FLAME, target.getLocation().add(0, 1, 0), 35, 0.4, 0.6, 0.4, 0.04);
                target.playSound(target.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1f, 1.2f);
                player.sendMessage("§c[봉풀주] §f" + target.getName() + "에게 점화");
                return true;
            }
            case CLEANSE -> {
                cleanse(player);
                player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation().add(0, 1, 0), 25, 0.4, 0.7, 0.4, 0.03);
                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.5f);
                player.sendMessage("§f[봉풀주] §b정화!");
                return true;
            }
            case SMITE -> {
                Player target = getTargetPlayer(player);
                if (target == null) {
                    player.sendMessage("§c[봉풀주] 강타를 사용할 대상이 없습니다.");
                    return false;
                }

                target.damage(6.0, player);
                target.getWorld().strikeLightningEffect(target.getLocation());
                target.playSound(target.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f, 1.6f);
                player.sendMessage("§6[봉풀주] §f" + target.getName() + "에게 강타!");
                return true;
            }
        }

        return false;
    }

    @EventHandler
    public void onLeftClick(PlayerInteractEvent event) {
        if (!holders.contains(event.getPlayer().getUniqueId())) return;
        if (event.getItem() == null || event.getItem().getType() != Material.NETHER_STAR) return;
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) return;

        event.setCancelled(true);
        openSpellUI(event.getPlayer());
    }

    private void openSpellUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "§d봉풀주 - 스펠 선택");

        setSpellItem(inv, 9, Spell.FLASH);
        setSpellItem(inv, 10, Spell.GHOST);
        setSpellItem(inv, 11, Spell.HEAL);
        setSpellItem(inv, 12, Spell.BARRIER);
        setSpellItem(inv, 14, Spell.EXHAUST);
        setSpellItem(inv, 15, Spell.IGNITE);
        setSpellItem(inv, 16, Spell.CLEANSE);
        setSpellItem(inv, 17, Spell.SMITE);

        player.openInventory(inv);
    }

    private void setSpellItem(Inventory inv, int slot, Spell spell) {
        ItemStack item = new ItemStack(spell.wool);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(spell.color + spell.displayName);
            meta.setLore(List.of("§7클릭해서 이 스펠로 변경합니다."));
            meta.getPersistentDataContainer().set(spellKey, PersistentDataType.STRING, spell.name());
            item.setItemMeta(meta);
        }

        inv.setItem(slot, item);
    }

    @EventHandler
    public void onSpellClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!holders.contains(player.getUniqueId())) return;
        if (!event.getView().getTitle().equals("§d봉풀주 - 스펠 선택")) return;

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;

        String raw = meta.getPersistentDataContainer().get(spellKey, PersistentDataType.STRING);
        if (raw == null) return;

        Spell spell;
        try {
            spell = Spell.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return;
        }

        selectedSpell.put(player.getUniqueId(), spell);
        player.closeInventory();

        Bukkit.broadcastMessage("§d[봉풀주] §f" + player.getName() + "님이 스펠을 " + spell.color + spell.displayName + "§f(으)로 변경했습니다!");
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.6f);
    }

    private Player getTargetPlayer(Player player) {
        Entity target = player.getTargetEntity((int) TARGET_RANGE, false);
        if (target instanceof Player p && !p.equals(player)) {
            return p;
        }
        return null;
    }

    private boolean useFlash(Player player) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        Location best = player.getLocation();
        for (double d = 1.0; d <= 8.0; d += 0.5) {
            Location check = eye.clone().add(dir.clone().multiply(d));
            Location feet = check.clone();
            feet.setY(check.getY());

            if (!isSafe(feet)) {
                break;
            }

            best = feet;
        }

        if (best.distance(player.getLocation()) < 1.0) {
            player.sendMessage("§c[봉풀주] 점멸할 공간이 없습니다.");
            return false;
        }

        best.setYaw(player.getLocation().getYaw());
        best.setPitch(player.getLocation().getPitch());

        player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.1);
        player.teleport(best);
        player.getWorld().spawnParticle(Particle.PORTAL, best.clone().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.1);
        player.playSound(best, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.4f);
        player.sendMessage("§d[봉풀주] §f점멸!");
        return true;
    }

    private boolean isSafe(Location loc) {
        World world = loc.getWorld();
        if (world == null) return false;

        Location feet = loc.clone();
        Location head = loc.clone().add(0, 1, 0);

        return feet.getBlock().isPassable()
                && head.getBlock().isPassable()
                && !loc.clone().subtract(0, 1, 0).getBlock().isPassable();
    }

    private void cleanse(Player player) {
        PotionEffectType[] remove = {
                PotionEffectType.SLOWNESS,
                PotionEffectType.WEAKNESS,
                PotionEffectType.POISON,
                PotionEffectType.WITHER,
                PotionEffectType.BLINDNESS,
                PotionEffectType.DARKNESS
        };

        for (PotionEffectType type : remove) {
            player.removePotionEffect(type);
        }

        player.setFireTicks(0);
    }

    private enum Spell {
        FLASH("점멸", "§d", Material.PURPLE_WOOL),
        GHOST("유체화", "§b", Material.LIGHT_BLUE_WOOL),
        HEAL("회복", "§a", Material.LIME_WOOL),
        BARRIER("방어막", "§e", Material.YELLOW_WOOL),
        EXHAUST("탈진", "§8", Material.GRAY_WOOL),
        IGNITE("점화", "§c", Material.RED_WOOL),
        CLEANSE("정화", "§f", Material.WHITE_WOOL),
        SMITE("강타", "§6", Material.ORANGE_WOOL);

        final String displayName;
        final String color;
        final Material wool;

        Spell(String displayName, String color, Material wool) {
            this.displayName = displayName;
            this.color = color;
            this.wool = wool;
        }
    }
}