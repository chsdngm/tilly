package com.imbananko.tilly.utility

import com.imbananko.tilly.model.VoteValue
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User

fun Update.isP2PChat(): Boolean = this.hasMessage() && this.message.chat.isUserChat

fun Update.hasPhoto(): Boolean = this.hasMessage() && this.message.hasPhoto()

fun Update.hasVote(): Boolean =
    this.hasCallbackQuery() && runCatching {
      setOf(*VoteValue.values()).contains(extractVoteValue())
    }.getOrDefault(false)

fun Update.canBeExplanation(): Boolean =
    this.hasMessage() && this.message?.replyToMessage?.from?.bot ?: false && runCatching {
      this.message.replyToMessage.text.endsWith("поясни за мем, на это у тебя есть сутки")
    }.getOrDefault(false)

fun Update.extractVoteValue(): VoteValue =
    VoteValue.valueOf(this.callbackQuery.data.split(" ".toRegex()).dropLastWhile { it.isEmpty() }[0])

fun User.mention(): String =
    "[${this.userName ?: this.firstName ?: "мутный тип"}](tg://user?id=${this.id})"