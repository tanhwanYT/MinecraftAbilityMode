package my.pkg;

import my.pkg.abilities.Ability;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class AbilityPickManager implements Listener {

    private static final String PICK_TITLE = "능력 선택";
    private static final String REWARD_TITLE = "랜덤 보상 선택 (2개)";
    private static final int PICK_SIZE = 54;
    private static final int REWARD_SIZE = 27;
    private static final int REWARD_PICK_COUNT = 2;

    private final JavaPlugin plugin;
    private final AbilitySystem abilitySystem;
    private final SupplyManager supplyManager;
    private final GameManager gameManager;

    private final NamespacedKey abilityKey;
    private final NamespacedKey randomKey;
    private final NamespacedKey rewardKey;
    private final NamespacedKey rewardRandomKey;

    private final Map<String, AbilityInfo> abilityInfos = new LinkedHashMap<>();
    private final Map<String, RewardInfo> rewardInfos = new LinkedHashMap<>();

    private final Set<UUID> awaitingAbilityPick = new HashSet<>();
    private final Set<UUID> awaitingRewardPick = new HashSet<>();
    private final Map<UUID, Set<String>> selectedRewardIds = new HashMap<>();
    private final Map<UUID, BukkitTask> reopenTasks = new HashMap<>();

    public AbilityPickManager(JavaPlugin plugin,
                              AbilitySystem abilitySystem,
                              SupplyManager supplyManager,
                              GameManager gameManager) {
        this.plugin = plugin;
        this.abilitySystem = abilitySystem;
        this.supplyManager = supplyManager;
        this.gameManager = gameManager;

        this.abilityKey = new NamespacedKey(plugin, "pick_ability_id");
        this.randomKey = new NamespacedKey(plugin, "pick_random");
        this.rewardKey = new NamespacedKey(plugin, "pick_reward_id");
        this.rewardRandomKey = new NamespacedKey(plugin, "pick_reward_random");

        registerAbilityInfos();
        registerRewardInfos();
    }

    public void startPickGame(CommandSender sender) {
        if (abilitySystem.getRegisteredAbilities().isEmpty()) {
            sender.sendMessage("No abilities registered.");
            return;
        }

        for (Player p : plugin.getServer().getOnlinePlayers()) {
            cancelReopenTask(p.getUniqueId());

            abilitySystem.grant(p, null);
            abilitySystem.giveDefaultStartItems(p, false); // 리롤권 X

            awaitingAbilityPick.add(p.getUniqueId());
            awaitingRewardPick.remove(p.getUniqueId());
            selectedRewardIds.remove(p.getUniqueId());

            openAbilityPickInventory(p);
            p.sendMessage("§e원하는 능력을 선택하세요!");
        }

        gameManager.startGame();
        sender.sendMessage("§aAbility pick game started!");
    }

    private void openAbilityPickInventory(Player player) {
        Inventory inv = Bukkit.createInventory(null, PICK_SIZE, PICK_TITLE);

        int slot = 0;
        for (Ability ability : abilitySystem.getRegisteredAbilities()) {
            AbilityInfo info = abilityInfos.get(ability.id().toLowerCase());
            if (info == null) continue;
            if (slot >= 45) break;

            inv.setItem(slot++, createAbilityItem(info, ability));
        }

        inv.setItem(49, createRandomAbilityItem());
        player.openInventory(inv);
    }

    private ItemStack createAbilityItem(AbilityInfo info, Ability ability) {
        ItemStack item = new ItemStack(info.icon());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§b" + info.name());
            meta.setLore(List.of(
                    "§7ID: §f" + ability.id(),
                    "§7쿨타임: §f" + ability.cooldownSeconds() + "초",
                    "",
                    "§f" + info.summary(),
                    "",
                    "§e클릭하여 선택"
            ));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(abilityKey, PersistentDataType.STRING, ability.id());
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createRandomAbilityItem() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§d랜덤 능력");
            meta.setLore(List.of(
                    "§7능력을 랜덤으로 추첨합니다.",
                    "§6대신 보급 아이템 2개를 직접 고를 수 있습니다.",
                    "",
                    "§e클릭하여 랜덤 선택"
            ));
            meta.addEnchant(org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.getPersistentDataContainer().set(randomKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void openRewardPickInventory(Player player) {
        Inventory inv = Bukkit.createInventory(null, REWARD_SIZE, REWARD_TITLE);

        int slot = 0;
        for (RewardInfo info : rewardInfos.values()) {
            if (slot >= 26) break; // 마지막 칸 하나는 랜덤 버튼용
            inv.setItem(slot++, createRewardItem(info));
        }

        inv.setItem(26, createRandomRewardItem());
        player.openInventory(inv);
    }

    private ItemStack createRewardItem(RewardInfo info) {
        ItemStack item = new ItemStack(info.icon());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§a" + info.name());
            meta.setLore(List.of(
                    "§f" + info.summary(),
                    "",
                    "§e클릭하여 선택"
            ));
            meta.getPersistentDataContainer().set(rewardKey, PersistentDataType.STRING, info.id());
            item.setItemMeta(meta);
        }
        return item;
    }

    private String pickRandomRewardId(UUID playerId) {
        Set<String> picked = selectedRewardIds.computeIfAbsent(playerId, k -> new LinkedHashSet<>());

        List<String> candidates = new ArrayList<>();
        for (String id : rewardInfos.keySet()) {
            if (!picked.contains(id)) {
                candidates.add(id);
            }
        }

        if (candidates.isEmpty()) return null;

        return candidates.get(new Random().nextInt(candidates.size()));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        String title = event.getView().getTitle();

        if (PICK_TITLE.equals(title)) {
            event.setCancelled(true);

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;

            ItemMeta meta = clicked.getItemMeta();

            String abilityId = meta.getPersistentDataContainer().get(abilityKey, PersistentDataType.STRING);
            Byte isRandom = meta.getPersistentDataContainer().get(randomKey, PersistentDataType.BYTE);

            if (abilityId != null) {
                Ability ability = abilitySystem.getAbility(abilityId);
                if (ability == null) {
                    player.sendMessage("§c해당 능력을 찾을 수 없습니다.");
                    return;
                }

                awaitingAbilityPick.remove(player.getUniqueId());
                cancelReopenTask(player.getUniqueId());

                abilitySystem.grant(player, ability);
                player.closeInventory();
                showPickedAbility(player, ability.name());

                return;
            }

            if (isRandom != null && isRandom == (byte) 1) {
                List<Ability> abilities = new ArrayList<>(abilitySystem.getRegisteredAbilities());
                if (abilities.isEmpty()) {
                    player.sendMessage("§c등록된 능력이 없습니다.");
                    return;
                }

                Ability randomAbility = abilities.get(new Random().nextInt(abilities.size()));

                awaitingAbilityPick.remove(player.getUniqueId());
                awaitingRewardPick.add(player.getUniqueId());
                selectedRewardIds.put(player.getUniqueId(), new LinkedHashSet<>());
                cancelReopenTask(player.getUniqueId());

                abilitySystem.grant(player, randomAbility);
                player.sendMessage("§d[랜덤] §f당신의 능력은 §a" + randomAbility.name() + "§f입니다!");
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);

                Bukkit.getScheduler().runTask(plugin, () -> openRewardPickInventory(player));
            }
            return;
        }

        if (REWARD_TITLE.equals(title)) {
            event.setCancelled(true);

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;

            ItemMeta meta = clicked.getItemMeta();

            Byte isRandomReward = meta.getPersistentDataContainer().get(rewardRandomKey, PersistentDataType.BYTE);
            String rewardId = meta.getPersistentDataContainer().get(rewardKey, PersistentDataType.STRING);

            if (isRandomReward != null && isRandomReward == (byte) 1) {
                rewardId = pickRandomRewardId(player.getUniqueId());

                if (rewardId == null) {
                    player.sendMessage("§c더 이상 뽑을 수 있는 랜덤 보상이 없습니다.");
                    return;
                }
            }

            if (rewardId == null) return;

            Set<String> picked = selectedRewardIds.computeIfAbsent(
                    player.getUniqueId(),
                    k -> new LinkedHashSet<>()
            );

            if (picked.contains(rewardId)) {
                player.sendMessage("§c이미 고른 보급 아이템입니다.");
                return;
            }

            picked.add(rewardId);

            RewardInfo info = rewardInfos.get(rewardId);
            if (info != null) {
                player.sendMessage("§a보급 선택: §f" + info.name() + " §7(" + picked.size() + "/" + REWARD_PICK_COUNT + ")");
            }

            if (picked.size() >= REWARD_PICK_COUNT) {
                for (String id : picked) {
                    supplyManager.giveSupplyItem(player, id);
                }

                awaitingRewardPick.remove(player.getUniqueId());
                selectedRewardIds.remove(player.getUniqueId());
                player.closeInventory();
                player.sendMessage("§6보급 보상 선택이 완료되었습니다!");
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
            } else {
                // 1개만 골랐으면 다시 창 갱신해서 중복 방지 체감 좋게
                Bukkit.getScheduler().runTask(plugin, () -> openRewardPickInventory(player));
            }
        }
    }

    private ItemStack createRandomRewardItem() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§d랜덤 보상");
            meta.setLore(List.of(
                    "§7남아있는 보상 중 하나를",
                    "§7랜덤으로 선택합니다.",
                    "",
                    "§e클릭하여 랜덤 선택"
            ));
            meta.addEnchant(org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.getPersistentDataContainer().set(rewardRandomKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        String title = event.getView().getTitle();
        UUID uuid = player.getUniqueId();

        if (PICK_TITLE.equals(title) && awaitingAbilityPick.contains(uuid)) {
            abilitySystem.grant(player, null);
            player.sendMessage("§cesc를 누르다니 원하는 능력이 없는 모양이네요! 무능력자로 플레이 하세요!");

            cancelReopenTask(uuid);
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                if (!awaitingAbilityPick.contains(uuid)) return;

                player.sendMessage("§e하하 농담이에요.");
                openAbilityPickInventory(player);
            }, 20L * 10);

            reopenTasks.put(uuid, task);
        }

        if (REWARD_TITLE.equals(title) && awaitingRewardPick.contains(uuid)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                if (!awaitingRewardPick.contains(uuid)) return;
                player.sendMessage("§e랜덤 보상 2개를 모두 골라야 합니다.");
                openRewardPickInventory(player);
            }, 1L);
        }
    }

    private void showPickedAbility(Player player, String abilityName) {
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!player.isOnline() || t > 60) {
                    cancel();
                    return;
                }

                player.sendActionBar("§6당신의 능력: §e" + abilityName);
                t += 10;
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    private void cancelReopenTask(UUID uuid) {
        BukkitTask old = reopenTasks.remove(uuid);
        if (old != null) old.cancel();
    }

    private void putAbility(AbilityInfo info) {
        abilityInfos.put(info.id().toLowerCase(), info);
    }

    private void putReward(RewardInfo info) {
        rewardInfos.put(info.id(), info);
    }

    private void registerAbilityInfos() {
        putAbility(new AbilityInfo("malphite", "말파이트", "사용 시 상대를 느리게 만들고, 벽을 부수며 초고속 돌진. 1초 후 주변 적에게 피해와 에어본을 줌", Material.STONE));
        putAbility(new AbilityInfo("viper", "바이퍼", "사용자 중심 반경 9칸 독가스 지대 생성, 적에게 둔화+독 디버프 부여", Material.POTION));
        putAbility(new AbilityInfo("kiyathow", "끼얏호우", "폭죽과 함께 8초간 속도/점프강화/재생/성급함/흡수 등 다중 버프 획득", Material.FIREWORK_ROCKET));
        putAbility(new AbilityInfo("bomberman", "붐버맨", "시간마다 TNT 충전(최대 10개), 사용 시 충전된 TNT 전부 소환. 폭발 피해 면역", Material.TNT));
        putAbility(new AbilityInfo("antman", "앤트맨", "자신 크기를 15초 동안 랜덤 변경. 커지면 최대체력 증가, 작아지면 최대체력 감소 및 속도 강화", Material.RED_MUSHROOM));
        putAbility(new AbilityInfo("speeding", "속도위반", "5초간 노란 양털 위에서 가속, 다른 플레이어와 부딪히면 밀쳐냄. 종료 후 감속 페널티", Material.LEATHER_BOOTS));
        putAbility(new AbilityInfo("panic", "패닉", "가장 가까운 플레이어와 위치 교체, 대상과 본인에게 혼란/실명/인벤토리 룰렛 디버프", Material.CHORUS_FRUIT));
        putAbility(new AbilityInfo("sniper", "스나이퍼", "시즈모드 3초 조준 후 강한 탄환 발사(고데미지)", Material.CROSSBOW));
        putAbility(new AbilityInfo("donation", "도네이션", "바라보는 플레이어를 스턴, 대상은 쉬프트 연타로 해제 가능", Material.GOLD_INGOT));
        putAbility(new AbilityInfo("taliyah", "탈리아", "벽을 세우며 돌진 이동, 생성된 벽은 일정 시간 후 복구", Material.SANDSTONE));
        putAbility(new AbilityInfo("joker", "조커", "자신을 죽인 플레이어를 함께 죽이는 미러링 효과", Material.NAME_TAG));
        putAbility(new AbilityInfo("gambler", "도박꾼", "맨손 공격 시 70% 확률로 상대에게 3~7 랜덤 고정 피해 / 30% 확률로 본인 피해", Material.MAGMA_CREAM));
        putAbility(new AbilityInfo("glow", "라이징스타", "능력 사용 시 3초 뒤 섬광탄과 함께 6초 동안 투명 상태 돌입", Material.NETHER_STAR));
        putAbility(new AbilityInfo("bodyguard", "보디가드", "랜덤 보호대상 지정, 대상 생존 시 최대체력과 이속 버프 / 사망 시 버프 해제", Material.SHIELD));
        putAbility(new AbilityInfo("hitman", "청부업자", "랜덤 청부대상 추적, 직접 처치 시 스택형 버프 획득 및 새 대상 지정", Material.IRON_SWORD));
        putAbility(new AbilityInfo("set", "세트", "싸우면서 투지 스택 충전. 사용 시 1초 후 전방에 투지 스택 비례 부채꼴 공격", Material.IRON_CHESTPLATE));
        putAbility(new AbilityInfo("slotmachine", "슬롯머신", "버프 종류, 버프 레벨, 버프 시간을 랜덤으로 돌림. 1% 확률 즉사, 1% 확률 최대체력 1줄 추가", Material.EMERALD));
        putAbility(new AbilityInfo("stone", "돌", "한 자리에 오래 있으면 최대체력 서서히 증가. 자리 이탈 시 서서히 감소", Material.COBBLESTONE));
        putAbility(new AbilityInfo("palermo", "팔레르모", "1 VS 1 상황에서 강해지는 근접 특화 패시브", Material.BLAZE_POWDER));
        putAbility(new AbilityInfo("fisher", "피셔", "낚싯대로 물고기를 낚고, 물고기로 때리거나 먹어 활용 가능", Material.FISHING_ROD));
        putAbility(new AbilityInfo("shadowstep", "섀도우스탭", "바라보는 엔티티의 뒤로 이동. 1초 후 원래 자리로 복귀, 대상은 1초간 위치와 시야 고정", Material.ENDER_PEARL));
        putAbility(new AbilityInfo("chainarm", "사슬팔", "사슬을 날려 적을 끌어오거나 블록에 걸어 이동. 실패 시 페널티", Material.LEAD));
        putAbility(new AbilityInfo("glasscannon", "유리대포", "체력은 줄지만 공격력이 증가. 공격력/체력을 직접 조절 가능", Material.GLASS));
        putAbility(new AbilityInfo("hotspring", "온탕", "상대와 같은 물에 들어가 있으면 상대가 용암틱 피해를 입음. 자신은 피해 감소", Material.WATER_BUCKET));
        putAbility(new AbilityInfo("guillotine", "단두대", "바라보는 3x3 지역에 모루를 떨어뜨려 공격. 본인은 모루 피해 면역", Material.ANVIL));
        putAbility(new AbilityInfo("archer", "아처", "근접 공격 불가. 전용 활/화살 지급, 능력 사용 및 처치 시 화살 수급", Material.BOW));
        putAbility(new AbilityInfo("reporter", "기자", "원하는 플레이어 능력 전체공개, 대상은 발광과 스턴에 걸림", Material.SPYGLASS));
        putAbility(new AbilityInfo("backattacker", "백어택커", "뒤를 공격하면 추가 피해. 능력 사용 시 바라보는 방향으로 도약", Material.GOLDEN_SWORD));
        putAbility(new AbilityInfo("bangbang", "방방", "공중에 있을 때 피해를 받지 않음. 최대 체력은 8칸", Material.SLIME_BLOCK));
        putAbility(new AbilityInfo("batman", "4번타자", "밀치기 2 배트를 받음. 네더의 별 우클릭 시 야구공(화염구) 소환", Material.STICK));
    }

    private void registerRewardInfos() {
        putReward(new RewardInfo("bridge_egg", "브릿지 에그", "달걀을 던지면 궤적에 양털 다리가 생성되고 20초 뒤 사라집니다.", Material.EGG));
        putReward(new RewardInfo("diamond_helmet", "다이아 투구", "보호 1 다이아몬드 투구를 지급받습니다.", Material.DIAMOND_HELMET));
        putReward(new RewardInfo("trap", "트랩", "보이지 않는 함정을 설치합니다. 상대가 밟으면 피해와 함께 땅이 꺼집니다.", Material.TRIPWIRE_HOOK));
        putReward(new RewardInfo("midas's_hand", "미다스의 손", "때린 상대의 방어구 중 하나를 철에서 금으로 바꿉니다.", Material.GOLD_INGOT));
        putReward(new RewardInfo("gambler_diamond", "도박꾼의 다이아", "30% 확률로 상대 즉사, 70% 확률로 본인 즉사", Material.DIAMOND));
        putReward(new RewardInfo("ender_pearl", "엔더진주", "엔더진주 1개를 지급받습니다.", Material.ENDER_PEARL));
        putReward(new RewardInfo("fire_ticket", "발화 인챈트권", "가지고 있는 철검에 발화 인챈트를 부여합니다.", Material.ENCHANTED_BOOK));
        putReward(new RewardInfo("scientist_secret", "과학자의 토템", "정체를 알 수 없는 불사의 토템 1개를 지급받습니다.", Material.TOTEM_OF_UNDYING));
        putReward(new RewardInfo("adaptive_shield", "적응형 보호막", "우클릭 시 주변 플레이어 수에 비례해 노란 체력을 얻습니다.", Material.SHIELD));
        putReward(new RewardInfo("old_punishment_postcard", "낡은 징벌의 엽서", "사용 시 랜덤 플레이어 1명에게 독과 구속을 5초 부여합니다.", Material.PAPER));
        putReward(new RewardInfo("thor_trident", "토르의 삼지창", "던지면 착지 지점에 약한 번개가 내리칩니다.", Material.TRIDENT));
        putReward(new RewardInfo("stat_anvil", "능력치 모루", "우클릭시 랜덤 능력치를 획득합니다.", Material.ANVIL));
    }
}