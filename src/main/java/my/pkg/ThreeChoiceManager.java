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
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class ThreeChoiceManager implements Listener {

    private static final String ABILITY_TITLE = "§b능력 3지선다";
    private static final String ITEM_TITLE = "§a보급 아이템 3지선다";

    private final JavaPlugin plugin;
    private final AbilitySystem abilitySystem;
    private final SupplyManager supplyManager;
    private final GameManager gameManager;

    private final NamespacedKey abilityKey;
    private final NamespacedKey rewardKey;

    private final Map<UUID, List<Ability>> abilityChoices = new HashMap<>();
    private final Map<UUID, List<RewardInfo>> rewardChoices = new HashMap<>();

    private final Set<UUID> awaitingAbility = new HashSet<>();
    private final Set<UUID> awaitingReward = new HashSet<>();
    private final Map<String, AbilityInfo> abilityInfos = new LinkedHashMap<>();

    private final Map<UUID, BukkitTask> reopenTasks = new HashMap<>();
    private final Random random = new Random();

    private final List<RewardInfo> rewards = new ArrayList<>();

    public ThreeChoiceManager(JavaPlugin plugin,
                              AbilitySystem abilitySystem,
                              SupplyManager supplyManager,
                              GameManager gameManager) {
        this.plugin = plugin;
        this.abilitySystem = abilitySystem;
        this.supplyManager = supplyManager;
        this.gameManager = gameManager;

        this.abilityKey = new NamespacedKey(plugin, "three_choice_ability");
        this.rewardKey = new NamespacedKey(plugin, "three_choice_reward");

        registerAbilityInfos();
        registerRewards();
    }

    public void startThreeChoiceGame(CommandSender sender) {
        if (abilitySystem.getRegisteredAbilities().isEmpty()) {
            sender.sendMessage("§c등록된 능력이 없습니다.");
            return;
        }

        if (rewards.size() < 3) {
            sender.sendMessage("§c3지선다 보급 아이템이 3개 미만입니다.");
            return;
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            cancelReopenTask(p.getUniqueId());

            abilitySystem.grant(p, null);
            abilitySystem.giveDefaultStartItems(p, false); // 리롤권 X

            List<Ability> pickedAbilities = pickRandomAbilities(3);
            List<RewardInfo> pickedRewards = pickRandomRewards(3);

            abilityChoices.put(p.getUniqueId(), pickedAbilities);
            rewardChoices.put(p.getUniqueId(), pickedRewards);

            awaitingAbility.add(p.getUniqueId());
            awaitingReward.remove(p.getUniqueId());

            openAbilityUI(p);
            p.sendMessage("§b[3지선다] §f능력 3개 중 하나를 선택하세요!");
        }

        gameManager.startGame();
        sender.sendMessage("§a3지선다 모드가 시작되었습니다!");
    }

    private List<Ability> pickRandomAbilities(int count) {
        List<Ability> list = new ArrayList<>(abilitySystem.getRegisteredAbilities());
        Collections.shuffle(list);
        return new ArrayList<>(list.subList(0, Math.min(count, list.size())));
    }

    private List<RewardInfo> pickRandomRewards(int count) {
        List<RewardInfo> list = new ArrayList<>(rewards);
        Collections.shuffle(list);
        return new ArrayList<>(list.subList(0, Math.min(count, list.size())));
    }

    private void openAbilityUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, ABILITY_TITLE);

        List<Ability> choices = abilityChoices.get(player.getUniqueId());
        if (choices == null || choices.isEmpty()) return;

        int[] slots = {11, 13, 15};

        for (int i = 0; i < choices.size() && i < 3; i++) {
            Ability ability = choices.get(i);
            inv.setItem(slots[i], createAbilityItem(ability));
        }

        player.openInventory(inv);
    }

    private ItemStack createAbilityItem(Ability ability) {

        AbilityInfo info = abilityInfos.get(ability.id().toLowerCase());

        Material icon = Material.NETHER_STAR;
        String summary = "설명 없음";
        String displayName = ability.name();

        if (info != null) {
            icon = info.icon();
            summary = info.summary();
            displayName = info.name();
        }

        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName("§b§l" + displayName);

            meta.setLore(List.of(
                    "§7ID: §f" + ability.id(),
                    "§7쿨타임: §f" + ability.cooldownSeconds() + "초",
                    "",
                    "§f" + summary,
                    "",
                    "§e클릭하여 이 능력 선택"
            ));

            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

            meta.getPersistentDataContainer().set(
                    abilityKey,
                    PersistentDataType.STRING,
                    ability.id()
            );

            item.setItemMeta(meta);
        }

        return item;
    }

    private void openRewardUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, ITEM_TITLE);

        List<RewardInfo> choices = rewardChoices.get(player.getUniqueId());
        if (choices == null || choices.isEmpty()) return;

        int[] slots = {11, 13, 15};

        for (int i = 0; i < choices.size() && i < 3; i++) {
            RewardInfo reward = choices.get(i);
            inv.setItem(slots[i], createRewardItem(reward));
        }

        player.openInventory(inv);
    }

    private ItemStack createRewardItem(RewardInfo reward) {
        ItemStack item = new ItemStack(reward.icon());
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName("§a§l" + reward.name());
            meta.setLore(List.of(
                    "§f" + reward.summary(),
                    "",
                    "§e클릭하여 이 보급 아이템 선택"
            ));

            meta.getPersistentDataContainer().set(rewardKey, PersistentDataType.STRING, reward.id());

            item.setItemMeta(meta);
        }

        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        String title = event.getView().getTitle();

        if (ABILITY_TITLE.equals(title)) {
            event.setCancelled(true);

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;

            ItemMeta meta = clicked.getItemMeta();
            String abilityId = meta.getPersistentDataContainer().get(abilityKey, PersistentDataType.STRING);
            if (abilityId == null) return;

            Ability ability = abilitySystem.getAbility(abilityId);
            if (ability == null) {
                player.sendMessage("§c해당 능력을 찾을 수 없습니다.");
                return;
            }

            awaitingAbility.remove(player.getUniqueId());
            awaitingReward.add(player.getUniqueId());
            cancelReopenTask(player.getUniqueId());

            abilitySystem.grant(player, ability);

            player.closeInventory();
            player.sendMessage("§b[3지선다] §f능력 선택: §e" + ability.name());
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f);

            Bukkit.getScheduler().runTask(plugin, () -> openRewardUI(player));
            return;
        }

        if (ITEM_TITLE.equals(title)) {
            event.setCancelled(true);

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;

            ItemMeta meta = clicked.getItemMeta();
            String rewardId = meta.getPersistentDataContainer().get(rewardKey, PersistentDataType.STRING);
            if (rewardId == null) return;

            awaitingReward.remove(player.getUniqueId());
            rewardChoices.remove(player.getUniqueId());
            abilityChoices.remove(player.getUniqueId());
            cancelReopenTask(player.getUniqueId());

            supplyManager.giveSupplyItem(player, rewardId);

            player.closeInventory();
            player.sendMessage("§a[3지선다] §f보급 아이템을 선택했습니다!");
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);

        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        UUID uuid = player.getUniqueId();
        String title = event.getView().getTitle();

        if (ABILITY_TITLE.equals(title) && awaitingAbility.contains(uuid)) {
            cancelReopenTask(uuid);

            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                if (!awaitingAbility.contains(uuid)) return;

                player.sendMessage("§e[3지선다] 능력을 골라야 합니다.");
                openAbilityUI(player);
            }, 20L);

            reopenTasks.put(uuid, task);
        }

        if (ITEM_TITLE.equals(title) && awaitingReward.contains(uuid)) {
            cancelReopenTask(uuid);

            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                if (!awaitingReward.contains(uuid)) return;

                player.sendMessage("§e[3지선다] 보급 아이템을 골라야 합니다.");
                openRewardUI(player);
            }, 20L);

            reopenTasks.put(uuid, task);
        }
    }

    private void cancelReopenTask(UUID uuid) {
        BukkitTask old = reopenTasks.remove(uuid);
        if (old != null) old.cancel();
    }


    private void registerAbilityInfos() {
        putAbility(new AbilityInfo("malphite", "말파이트", "사용 시 상대를 느리게 만들고, 벽을 부수며 초고속 돌진. 1초 후 주변 적에게 피해와 에어본을 줌", Material.STONE));
        putAbility(new AbilityInfo("viper", "바이퍼", "사용자 중심 반경 9칸 독가스 지대 생성, 적에게 둔화+독 디버프 부여", Material.POTION));
        putAbility(new AbilityInfo("kiyathow", "끼얏호우", "폭죽과 함께 8초간 속도/점프강화/재생/성급함/흡수 등 다중 버프 획득", Material.FIREWORK_ROCKET));
        putAbility(new AbilityInfo("bomberman", "붐버맨", "시간마다 TNT 충전(최대 10개), 사용 시 충전된 TNT 전부 소환. 폭발 피해 면역", Material.TNT));
        putAbility(new AbilityInfo("antman", "앤트맨", "자신 크기를 15초 동안 랜덤 변경. 커지면 최대체력 증가, 작아지면 최대체력 감소 및 속도 강화", Material.RED_MUSHROOM));
        putAbility(new AbilityInfo("speeding", "속도위반", "5초간 노란 양털 위에서 가속, 다른 플레이어와 부딪히면 밀쳐냄. 종료 후 감속 페널티", Material.LEATHER_BOOTS));
        putAbility(new AbilityInfo("panic", "패닉", "가장 가까운 플레이어와 위치 교체, 대상과 본인에게 혼란/실명/인벤토리 룰렛 디버프", Material.CHORUS_FRUIT));
        putAbility(new AbilityInfo("sniper", "스나이퍼", "시즈모드 3초 조준 후 강한 탄환 발사(고데미지)", Material.END_ROD));
        putAbility(new AbilityInfo("donation", "도네이션", "바라보는 플레이어를 스턴, 대상은 쉬프트 연타로 해제 가능", Material.GOLD_INGOT));
        putAbility(new AbilityInfo("taliyah", "탈리아", "벽을 세우며 돌진 이동, 생성된 벽은 일정 시간 후 복구", Material.SANDSTONE));
        putAbility(new AbilityInfo("joker", "조커", "자신을 죽인 플레이어를 함께 죽이는 미러링 효과", Material.NAME_TAG));
        putAbility(new AbilityInfo("gambler", "도박꾼", "맨손 공격시 랜덤 고정 피해. 100%의 생존 확률이 공격할 때마다 1%씩 줄어듬. 생존 판정 실패 시 즉사 ", Material.MAGMA_CREAM));
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
        putAbility(new AbilityInfo("hotspring", "온탕", "상대와 같은 물에 들어가있으면 거리비례의 용암틱 대미지를 입음. 자신은 대미지를 덜 받음", Material.WATER_BUCKET));
        putAbility(new AbilityInfo("guillotine", "단두대", "바라보는 3x3 지역에 모루를 떨어뜨려 공격. 본인은 모루 피해 면역", Material.ANVIL));
        putAbility(new AbilityInfo("archer", "아처", "근접 공격 불가. 전용 활/화살 지급, 능력 사용 및 처치 시 화살 수급", Material.BOW));
        putAbility(new AbilityInfo("reporter", "기자", "원하는 플레이어 능력 전체공개, 대상은 발광과 스턴에 걸림", Material.SPYGLASS));
        putAbility(new AbilityInfo("backattacker", "백어택커", "뒤를 공격하면 추가 피해. 능력 사용 시 바라보는 방향으로 도약", Material.GOLDEN_SWORD));
        putAbility(new AbilityInfo("bangbang", "방방", "근처에 있는 모든 플레이어와 본인에게 점프강화 부여. 지속시간 동안 공중에 있을시 피해 면역", Material.SLIME_BLOCK));
        putAbility(new AbilityInfo("batman", "4번타자", "밀치기 2 배트를 받음. 네더의 별 우클릭 시 야구공(화염구) 소환, 야구공을 직접 타격시 더 빠르게 날아감", Material.STICK));
        putAbility(new AbilityInfo("blaze", "블레이즈", "일정 시간 비행하며 화염구를 날림. 물에 취약하고 갑옷 페널티가 있음", Material.BLAZE_ROD));
        putAbility(new AbilityInfo("ahab", "에이해브", "자신을 공격하지 않는 변명자 2마리 소환. 특정 채팅 대사로 근처 대상에게 피해", Material.VINDICATOR_SPAWN_EGG));
        putAbility(new AbilityInfo("wildcard", "와일드카드", "랜덤 액티브 능력을 발동. 발동 실패 시 쿨타임을 돌려받음", Material.AMETHYST_SHARD));
        putAbility(new AbilityInfo("doppelganger", "도플갱어", "분신을 소환해 유인. 본인은 은신하고 분신 조종 가능, 3초 뒤 폭발", Material.PLAYER_HEAD));
        putAbility(new AbilityInfo("daystar", "데이스타", "상대 발밑에 불을 붙이고 5초 뒤 폭발시킴", Material.FIRE_CHARGE));
        putAbility(new AbilityInfo("rapidcrossbow", "빠른석궁", "8초 동안 다중발사 석궁을 빠르게 연사. 강한 반동 주의", Material.CROSSBOW));
        putAbility(new AbilityInfo("warden", "워든", "주변 플레이어에게 어둠을 걸고 강한 워든 파동 발사. 밟는 블록을 스컬크로 감염", Material.SCULK_SHRIEKER));
        putAbility(new AbilityInfo("humancheck", "본인인증", "상대와 본인인증 대결 시작. 승리 시 버프, 패배 시 디버프", Material.WRITABLE_BOOK));
        putAbility(new AbilityInfo("humancheck", "본인인증", "상대와 본인인증 대결 시작. 승리 시 버프, 패배 시 디버프", Material.WRITABLE_BOOK));
        putAbility(new AbilityInfo("golem", "골렘", "타격 시 상대를 공중으로 날림. 최대체력 증가, 공격속도 감소", Material.IRON_BLOCK));
        putAbility(new AbilityInfo("spellbook", "봉풀주", "점멸, 유체화, 회복, 정화, 방어막, 탈진, 점화, 강타 중 원하는 스펠 사용", Material.ENCHANTED_BOOK));
    }

    private void registerRewards() {
        rewards.add(new RewardInfo("bridge_egg", "브릿지 에그", "던지면 궤적에 양털 다리가 생성됩니다.", Material.EGG));
        rewards.add(new RewardInfo("prot_helmet", "다이아 투구", "보호 1 다이아몬드 투구를 받습니다.", Material.DIAMOND_HELMET));
        rewards.add(new RewardInfo("trap", "트랩", "상대가 밟으면 피해와 함께 땅이 꺼지는 함정을 설치합니다.", Material.TRIPWIRE_HOOK));
        rewards.add(new RewardInfo("midas's_hand", "미다스의 손", "때린 상대의 방어구 하나를 금으로 바꿉니다.", Material.GOLD_INGOT));
        rewards.add(new RewardInfo("gambler_diamond", "도박꾼의 다이아", "30% 상대 즉사, 70% 본인 즉사.", Material.DIAMOND));
        rewards.add(new RewardInfo("ender_pearl", "엔더진주", "엔더진주 1개를 받습니다.", Material.ENDER_PEARL));
        rewards.add(new RewardInfo("fire_ticket", "발화 인챈트권", "철검에 발화 인챈트를 부여합니다.", Material.ENCHANTED_BOOK));
        rewards.add(new RewardInfo("scientist_secret", "과학자의 토템", "정체를 알 수 없는 불사의 토템.", Material.TOTEM_OF_UNDYING));
        rewards.add(new RewardInfo("adaptive_shield", "적응형 보호막", "주변 플레이어 수에 비례해 흡수 체력을 얻습니다.", Material.SHIELD));
        rewards.add(new RewardInfo("old_punishment_postcard", "낡은 징벌의 엽서", "랜덤 플레이어에게 독과 구속을 겁니다.", Material.PAPER));
        rewards.add(new RewardInfo("thor_trident", "토르의 삼지창", "던진 곳에 약한 번개를 내립니다.", Material.TRIDENT));
        rewards.add(new RewardInfo("stat_anvil", "능력치 모루", "랜덤 능력치를 획득합니다.", Material.ANVIL));
        rewards.add(new RewardInfo("monster_ball", "몬스터 볼", "상대를 5초 동안 몬스터볼에 가둡니다.", Material.SNOWBALL));
    }

    private void putAbility(AbilityInfo info) {
        abilityInfos.put(info.id().toLowerCase(), info);
    }

    private record AbilityInfo(
            String id,
            String name,
            String summary,
            Material icon
    ) {}
    private record RewardInfo(
            String id,
            String name,
            String summary,
            Material icon
    ) {}
}