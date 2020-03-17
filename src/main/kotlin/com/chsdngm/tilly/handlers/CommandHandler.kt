package com.chsdngm.tilly.handlers

import com.chsdngm.tilly.model.CommandUpdate
import com.chsdngm.tilly.model.CommandUpdate.Command
import com.chsdngm.tilly.model.MemeStatsEntry
import com.chsdngm.tilly.repository.VoteRepository
import com.chsdngm.tilly.utility.BotConfig
import com.chsdngm.tilly.utility.BotConfigImpl
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.telegram.telegrambots.meta.api.methods.ParseMode
import org.telegram.telegrambots.meta.api.methods.send.SendMessage

@Component
class CommandHandler(private val voteRepository: VoteRepository,
                     private val botConfig: BotConfigImpl) : AbstractHandler<CommandUpdate>(), BotConfig by botConfig {
  private val log = LoggerFactory.getLogger(javaClass)

  override fun handle(update: CommandUpdate) {
    when (update.value) {
      Command.STATS -> sendStats(update)
      Command.HELP, Command.START -> sendInfoMessage(update)
      else -> log.error("Unknown command from update=$update")
    }
  }

  private fun sendStats(update: CommandUpdate) =
      runCatching {
        execute(SendMessage()
            .setChatId(update.senderId)
            .setText(formatStatsMessage(voteRepository.getStatsByUser(update.senderId.toInt())))
        )
      }.onSuccess {
        log.info("Sent stats to user=${update.senderId}")
      }.onFailure {
        log.error("Failed to send stats to user=$update", it)
      }

  private fun formatStatsMessage(stats: List<MemeStatsEntry>): String =
      if (stats.isEmpty())
        "You have no statistics yet!"
      else
        """
          Your statistics:

          Memes sent: ${stats.size}
        """.trimIndent() +
            stats
                .flatMap { it.counts.asIterable() }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.sum() }
                .toList()
                .sortedBy { it.first }
                .joinToString("\n", prefix = "\n\n", transform = { (value, sum) -> "${value.emoji}: $sum" })

  fun sendInfoMessage(update: CommandUpdate) {
    runCatching {
      execute(SendMessage()
          .setChatId(update.senderId)
          .setParseMode(ParseMode.HTML)
          .setText(infoText)
      )
    }.onSuccess {
      log.info("Sent info message to user=${update.senderId}")
    }.onFailure {
      log.error("Failed to send info message to user=$update", it)
    }
  }

  val infoText = """
      
      Привет, я ${botConfig.username}. 
      
      Чат со мной - это место для твоих лучших мемов, которыми охота поделиться.
      Сейчас же отправляй мне самый крутой мем, и, если он пройдёт модерацию, то попадёт на канал <a href="https://t.me/chsdngm/">че с деньгами</a>. 
      Мем, набравший за неделю больше всех кристаллов, станет <b>мемом недели</b>, а его обладатель получит бесконечный респект и поздравление на канале.

      Термины и определения:
      
      Мем (англ. meme) — единица культурной информации, имеющей развлекательный характер (в нашем случае - картинка). 
      Модерация (от лат. moderor — умеряю, сдерживаю) — все мемы проходят предварительную оценку экспертов, а на канал попадут только лучшие. 
      
      За динамикой оценки также можно следить тут.
      
    """.trimIndent()
}