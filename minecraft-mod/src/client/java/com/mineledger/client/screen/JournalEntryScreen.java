package com.mineledger.client.screen;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import com.mineledger.accounting.Account;
import com.mineledger.accounting.AccountingAnswer;
import com.mineledger.accounting.AccountingQuestion;
import com.mineledger.accounting.AccountingResult;
import com.mineledger.accounting.IntroJournalAssignment;
import com.mineledger.client.JournalSession;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Presentation only: all answer validation and XP awards live outside this screen. */
public final class JournalEntryScreen extends Screen {
    private enum AccountField { DEBIT, CREDIT }

    private final MineLedgerScreen parent;
    private final Set<String> correctThisAttempt = new HashSet<>();
    private int questionIndex;
    private Account debit = Account.CASH;
    private Account credit = Account.CASH;
    private String amountInput = "";
    private AccountingResult result;
    private AccountField expandedAccount;
    private ScrollableLayout scroll;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;

    public JournalEntryScreen(MineLedgerScreen parent) {
        super(Component.literal("Journal Entries"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(720, width - 24);
        panelHeight = Math.min(440, height - 24);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int formWidth = panelWidth - 64;
        LinearLayout form = LinearLayout.vertical().spacing(8);
        if (questionIndex == IntroJournalAssignment.QUESTIONS.size()) {
            text(form, "Assignment complete!", formWidth);
            text(form, "Score: " + correctThisAttempt.size() + " / 3 questions correct ("
                    + correctThisAttempt.size() * 10 + " / 30 points).", formWidth);
            text(form, "Session total: " + JournalSession.PROGRESS.correctCount()
                    + " / 3 unique questions correct; " + JournalSession.PROGRESS.xp() + " learning XP. "
                    + "Each question awards XP only once per client session. Progress is temporary and resets when Minecraft closes.", formWidth);
        } else {
            questionForm(form, formWidth);
        }
        scroll = new ScrollableLayout(minecraft, form, panelHeight - 90);
        scroll.arrangeElements();
        scroll.setX(left + (panelWidth - scroll.getWidth()) / 2);
        scroll.setY(top + 42);
        scroll.visitWidgets(this::addRenderableWidget);
        addRenderableWidget(Button.builder(Component.literal("Back to Assignments"), button -> onClose())
                .bounds(left + 16, top + panelHeight - 32, Math.min(180, panelWidth - 32), 20).build());
    }

    private void questionForm(LinearLayout form, int formWidth) {
        AccountingQuestion question = IntroJournalAssignment.QUESTIONS.get(questionIndex);
        text(form, "Question " + (questionIndex + 1) + " / 3", formWidth);
        text(form, question.prompt(), formWidth);
        text(form, "Record one debit and one credit for the same emerald amount. Open an account list to choose an account.", formWidth);
        boolean locked = result != null && result.valid();
        text(form, "Debit account", formWidth);
        accountDropdown(form, formWidth, AccountField.DEBIT, debit, question.debitAccounts(), locked, value -> debit = value);
        text(form, "Credit account", formWidth);
        accountDropdown(form, formWidth, AccountField.CREDIT, credit, question.creditAccounts(), locked, value -> credit = value);
        text(form, "Amount in emeralds (applies to both lines)", formWidth);
        EditBox amount = form.addChild(new EditBox(font, formWidth, 20, Component.literal("Emerald amount")));
        amount.setMaxLength(32);
        amount.setValue(amountInput);
        amount.setResponder(value -> amountInput = value);
        amount.setEditable(!locked);
        if (!locked) {
            form.addChild(Button.builder(Component.literal("Submit Answer"), button -> {
                result = JournalSession.PROGRESS.submit(question, new AccountingAnswer(debit, credit, amountInput));
                expandedAccount = null;
                if (result.correct()) correctThisAttempt.add(question.id());
                rebuildWidgets();
            }).size(formWidth, 20).build());
        }
        if (result != null) {
            text(form, result.correct() ? "Correct" : result.valid() ? "Incorrect" : "Check your input", formWidth);
            text(form, result.explanation(), formWidth);
            if (locked) {
                if (!result.correct()) {
                    form.addChild(Button.builder(Component.literal("Try Again"), button -> {
                        result = null;
                        rebuildWidgets();
                    }).size(formWidth, 20).build());
                }
                form.addChild(Button.builder(Component.literal("Next Question"), button -> {
                    questionIndex++;
                    expandedAccount = null;
                    debit = Account.CASH;
                    credit = Account.CASH;
                    amountInput = "";
                    result = null;
                    rebuildWidgets();
                }).size(formWidth, 20).build());
            }
        }
    }

    private void accountDropdown(LinearLayout form, int formWidth, AccountField field, Account selected,
            List<Account> accounts, boolean locked, Consumer<Account> onSelect) {
        boolean expanded = !locked && expandedAccount == field;
        Button toggle = form.addChild(Button.builder(accountLabel(selected).copy().append(expanded ? "  ▲" : "  ▼"), button -> {
            expandedAccount = expanded ? null : field;
            refreshDropdown();
        }).size(formWidth, 20).build());
        toggle.active = !locked;
        if (expanded) {
            LinearLayout options = form.addChild(LinearLayout.vertical().spacing(2));
            for (Account account : accounts) {
                Component label = Component.literal(account == selected ? "> " : "  ").append(accountLabel(account));
                options.addChild(Button.builder(label, button -> {
                    onSelect.accept(account);
                    expandedAccount = null;
                    refreshDropdown();
                }).size(formWidth, 20).build());
            }
        }
    }

    private void refreshDropdown() {
        double scrollAmount = scroll.getScrollAmount();
        rebuildWidgets();
        scroll.visitWidgets(widget -> {
            if (widget instanceof AbstractScrollArea area) area.setScrollAmount(scrollAmount);
        });
    }

    private void text(LinearLayout form, String value, int maxWidth) {
        form.addChild(new MultiLineTextWidget(Component.literal(value)
                .withStyle(style -> style.withColor(0x35383D).withoutShadow()), font).setMaxWidth(maxWidth));
    }

    private static Component accountLabel(Account account) {
        return Component.translatable("account.mineledger." + account.name().toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xFFEDEEF0);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xFFFFFFFF);
        graphics.outline(left, top, panelWidth, panelHeight, 0xFFCFD1D5);
        graphics.fill(left + 1, top + 1, left + panelWidth - 1, top + 30, 0xFFB6202D);
        graphics.text(font, title, left + 16, top + 11, 0xFFFFFFFF, false);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
