package cn.starfallplain.sfpmain.ui;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.ConfigManager;
import cn.starfallplain.sfpmain.config.Messages;
import cn.starfallplain.sfpmain.config.module.MenuConfig;
import cn.starfallplain.sfpmain.menu.MenuManager;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * dialogUI 版主菜单：用 Paper Dialog 弹窗渲染主菜单按钮。
 * <p>
 * 按钮集合、动作解析与箱子 UI **完全共用** {@link MenuManager#activeButtons} /
 * {@link MenuManager#resolveAction} / {@link MenuManager#runAction}，
 * 因此两种界面的按钮内容与点击结果一致。
 * <p>
 * 每个按钮挂一个 {@code customClick} 回调（Adventure 的 ClickCallback），
 * 点击时直接执行对应动作，无需经过命令中转 —— 这样才能支持「打开选人界面」等内部动作。
 */
public final class DialogMenu {

    /** 回调有效期：玩家打开菜单后有一段时间考虑，过期点击无效 */
    private static final Duration CALLBACK_LIFETIME = Duration.ofMinutes(10);

    private DialogMenu() {
    }

    /**
     * 打开 dialogUI 版主菜单。开关 + 打开权限校验与箱子 UI 共用 {@link MenuManager#canOpen}。
     */
    public static void open(SfpMain plugin, Player player) {
        if (!MenuManager.canOpen(plugin, player)) return;
        Dialog dialog = build(plugin);
        if (dialog == null) {
            player.sendMessage(plugin.getMessage("common.feature-disabled",
                    "<red>当前没有可用的菜单功能。</red>"));
            return;
        }
        player.showDialog(dialog);
    }

    /**
     * 构建主菜单 dialog（不显示）；没有可用按钮时返回 null。
     * <p>
     * 拆出来是为了让 {@code /sfp test} 也能在服务器环境跑一遍这条 Dialog API 调用链，
     * 否则「构建是否抛异常」只能靠真人开一次菜单才知道。
     */
    public static Dialog build(SfpMain plugin) {
        ConfigManager cm = plugin.getConfigManager();
        MenuConfig menuConfig = cm.menu();

        ClickCallback.Options options = ClickCallback.Options.builder()
                .uses(1)
                .lifetime(CALLBACK_LIFETIME)
                .build();

        List<ActionButton> buttons = new ArrayList<>();
        for (MenuConfig.Button button : MenuManager.activeButtons(menuConfig, cm)) {
            String action = MenuManager.resolveAction(button, menuConfig);
            Component label = Messages.deserialize(button.getName());

            ActionButton.Builder builder = ActionButton.builder(label);
            Component tooltip = buildTooltip(button);
            if (tooltip != null) {
                builder.tooltip(tooltip);
            }
            if (action == null) {
                // 说明性按钮（例如介绍告示牌椅子）：与箱子 UI 一样显示出来，但点击不做事
                buttons.add(builder.action(DialogAction.customClick((view, audience) -> {
                    // 空回调：仅占位，保持两种界面的按钮集合一致
                }, options)).build());
            } else {
                buttons.add(builder
                        .action(DialogAction.customClick((view, audience) -> {
                            if (audience instanceof Player p && p.isOnline()) {
                                MenuManager.runAction(plugin, p, action);
                            }
                        }, options))
                        .build());
            }
        }

        if (buttons.isEmpty()) return null;

        Component title = Messages.deserialize(menuConfig.getTitle());
        return Dialog.create(builder -> builder
                .empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).columns(2).build()));
    }

    /** 把 lore 多行合成一个 tooltip 组件；没有 lore 返回 null */
    private static Component buildTooltip(MenuConfig.Button button) {
        if (button.getLore() == null || button.getLore().isEmpty()) return null;
        Component tooltip = Component.empty();
        boolean first = true;
        for (String line : button.getLore()) {
            if (!first) {
                tooltip = tooltip.append(Component.newline());
            }
            tooltip = tooltip.append(Messages.deserialize(line));
            first = false;
        }
        return tooltip;
    }
}
