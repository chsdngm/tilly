package com.imbananko.tilly;

import com.imbananko.tilly.model.MemeEntity;
import com.imbananko.tilly.model.VoteEntity;
import com.imbananko.tilly.repository.MemeRepository;
import com.imbananko.tilly.repository.VoteRepository;
import com.imbananko.tilly.utility.TelegramPredicates;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.List;

import static com.imbananko.tilly.model.VoteEntity.Value.DOWN;
import static com.imbananko.tilly.model.VoteEntity.Value.UP;
import static io.vavr.API.*;

@Component
@Slf4j
public class MemeManager extends TelegramLongPollingBot {

  private final MemeRepository memeRepository;
  private final VoteRepository voteRepository;

  @Value("${target.chat.id}")
  private long chatId;

  @Value("${bot.token}")
  private String token;

  @Value("${bot.username}")
  private String username;

  @Autowired
  public MemeManager(MemeRepository memeRepository, VoteRepository voteRepository) {
    this.memeRepository = memeRepository;
    this.voteRepository = voteRepository;
  }

  @Override
  public String getBotToken() {
    return token;
  }

  @Override
  public String getBotUsername() {
    return username;
  }

  @Override
  public void onUpdateReceived(Update update) {
    Match(update)
        .of(
            Case($(TelegramPredicates.hasPhoto()), this::processMeme),
            Case($(TelegramPredicates.hasVote()), this::processVote),
            Case($(), () -> null));
  }

  private MemeEntity processMeme(Update update) {
    Message message = update.getMessage();
    MemeEntity meme =
        MemeEntity.builder()
            .authorUsername(message.getChat().getUserName())
            .targetChatId(chatId)
            .fileId(message.getPhoto().get(0).getFileId())
            .build();

    Try.of(
            () ->
                execute(
                    new SendPhoto()
                        .setChatId(chatId)
                        .setPhoto(meme.getFileId())
                        .setCaption("Sender: " + meme.getAuthorUsername())
                        .setReplyMarkup(createMarkup(meme))))
        .onSuccess(ignore -> log.info("Sent meme=" + meme))
        .onFailure(
            throwable ->
                log.error("Failed to send meme=" + meme + ". Exception=" + throwable.getMessage()));

    return memeRepository.save(meme);
  }

  private VoteEntity processVote(Update update) {
    final Message message = update.getCallbackQuery().getMessage();
    final MemeEntity meme =
        memeRepository.findById(message.getPhoto().get(0).getFileId()).orElseThrow();

    VoteEntity voteEntity =
        VoteEntity.builder()
            .chatId(chatId)
            .fileId(meme.getFileId())
            .username(update.getCallbackQuery().getFrom().getUserName())
            .value(VoteEntity.Value.valueOf(update.getCallbackQuery().getData()))
            .build();

    if (voteRepository.exists(voteEntity)) {
      voteRepository.delete(voteEntity);
    } else {
      voteRepository.save(voteEntity);
    }

    Try.of(
            () ->
                execute(
                    new EditMessageReplyMarkup()
                        .setMessageId(message.getMessageId())
                        .setChatId(message.getChatId())
                        .setInlineMessageId(update.getCallbackQuery().getInlineMessageId())
                        .setReplyMarkup(createMarkup(meme))))
        .onSuccess(ignore -> log.info("Updated meme=" + meme))
        .onFailure(
            throwable ->
                log.error(
                    "Failed to update meme=" + meme + ". Exception=" + throwable.getMessage()));

    return voteEntity;
  }

  private InlineKeyboardMarkup createMarkup(MemeEntity memeEntity) {
    return new InlineKeyboardMarkup()
        .setKeyboard(
            new ArrayList<>(
                List.of(
                    List.of(
                        new InlineKeyboardButton()
                            .setText(
                                voteRepository.countByFileIdAndChatIdAndValue(
                                            memeEntity.getFileId(),
                                            memeEntity.getTargetChatId(),
                                            UP)
                                        == 0
                                    ? UP.getEmoji()
                                    : UP.getEmoji()
                                        + " "
                                        + voteRepository.countByFileIdAndChatIdAndValue(
                                            memeEntity.getFileId(),
                                            memeEntity.getTargetChatId(),
                                            UP))
                            .setCallbackData(UP.name()),
                        new InlineKeyboardButton()
                            .setText(
                                voteRepository.countByFileIdAndChatIdAndValue(
                                            memeEntity.getFileId(),
                                            memeEntity.getTargetChatId(),
                                            DOWN)
                                        == 0
                                    ? DOWN.getEmoji()
                                    : DOWN.getEmoji()
                                        + " "
                                        + voteRepository.countByFileIdAndChatIdAndValue(
                                            memeEntity.getFileId(),
                                            memeEntity.getTargetChatId(),
                                            DOWN))
                            .setCallbackData(DOWN.name())))));
  }
}
