package com.imbananko.tilly.handlers

import com.imbananko.tilly.model.MemeEntity
import com.imbananko.tilly.model.MemeUpdate
import com.imbananko.tilly.repository.MemeRepository
import com.imbananko.tilly.similarity.MemeMatcher
import com.imbananko.tilly.utility.BotConfig
import com.imbananko.tilly.utility.BotConfigImpl
import com.imbananko.tilly.utility.isChatUserStatus
import org.apache.commons.io.IOUtils
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.telegram.telegrambots.meta.api.methods.ForwardMessage
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.annotation.PostConstruct

@Component
class MemeHandler(private val memeRepository: MemeRepository,
                  private val memeMatcher: MemeMatcher,
                  private val botConfig: BotConfigImpl) : AbstractHandler<MemeUpdate>(), BotConfig by botConfig {

  private val log = LoggerFactory.getLogger(javaClass)

  @PostConstruct
  private fun init() {
    memeRepository
        .findAllChatMemes(chatId)
        .distinctBy { it.fileId }
        .parallelStream()
        .map { meme -> Pair(meme.fileId, downloadFromFileId(meme.fileId)) }
        .forEach { (fileId, file) ->
          runCatching {
            memeMatcher.addMeme(fileId, file)
          }.onFailure { throwable: Throwable ->
            log.error("Failed to load file=$fileId", throwable)
          }
        }
  }

  override fun handle(update: MemeUpdate) {
    runCatching {
      val file = downloadFromFileId(update.fileId)

      memeMatcher.tryFindDuplicate(file)?.also { duplicate ->
        sendSorryText(update.senderId.toLong(), update.messageId)
        memeRepository.findMeme(duplicate)?.also { meme ->
          meme.channelMessageId?.apply { forwardMemeFromChannel(meme, update.senderId.toLong()) }
              ?: forwardMemeFromChat(meme, update.senderId.toLong())
        }
      } ?: sendMemeToChat(update).let { sent ->
        val privateMessageId = sendReplyToMeme(update).messageId
        memeRepository.save(MemeEntity(chatId, sent.messageId, update.senderId, update.fileId, privateMessageId)).also {
          memeMatcher.addMeme(it.fileId, file)
          log.info("Sent meme=$it to chat")
        }
      }
    }.onFailure { throwable ->
      log.error("Failed to check duplicates for update=$update", throwable)
    }
  }

  private fun sendMemeToChat(update: MemeUpdate) =
      execute(SendPhoto()
          .setChatId(chatId)
          .setPhoto(update.fileId)
          .setCaption(runCatching { getCaption(update) }.getOrThrow())
          .setReplyMarkup(createMarkup(emptyMap())))

  private fun getCaption(update: MemeUpdate): String =
      if (execute(GetChatMember()
              .setChatId(chatId)
              .setUserId(update.senderId))
              .status.isChatUserStatus()) {
        update.caption ?: ""
      } else {
        "${update.caption ?: ""}\n\nSender: ${update.senderName}"
      }

  private fun forwardMemeFromChannel(meme: MemeEntity, senderId: Long) {
    execute(ForwardMessage()
        .setChatId(senderId)
        .setFromChatId(meme.channelId)
        .setMessageId(meme.channelMessageId)
        .disableNotification())
    log.info("Successfully forwarded original meme to sender=$senderId. $meme")
  }

  private fun forwardMemeFromChat(meme: MemeEntity, senderId: Long) {
    execute(ForwardMessage()
        .setChatId(senderId)
        .setFromChatId(meme.chatId)
        .setMessageId(meme.messageId)
        .disableNotification())
    log.info("Successfully forwarded original meme to sender=$senderId. $meme")
  }

  private fun sendSorryText(senderId: Long, messageId: Int) =
      execute(SendMessage()
          .setChatId(senderId)
          .setReplyToMessageId(messageId)
          .disableNotification()
          .setText("К сожалению, мем уже был отправлен ранее!"))

  private fun sendReplyToMeme(update: MemeUpdate) =
      execute(SendMessage()
          .setChatId(update.senderId.toLong())
          .setReplyToMessageId(update.messageId)
          .disableNotification()
          .setText("${update.caption ?: ""}\n\nмем на модерации"))

  private fun downloadFromFileId(fileId: String) =
      File.createTempFile("photo-", "-" + Thread.currentThread().id + "-" + System.currentTimeMillis()).apply { this.deleteOnExit() }.also {
        FileOutputStream(it).use { out ->
          URL(execute(GetFile().setFileId(fileId)).getFileUrl(botToken)).openStream().use { stream -> IOUtils.copy(stream, out) }
        }
      }.also {
        log.info("Successfully downloaded file=$it")
      }
}
