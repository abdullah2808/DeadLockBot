import bot.handleCommands
import bot.registerCommands
import bot.startScheduler
import data.DatabaseFactory
import dev.kord.core.Kord

suspend fun main() {
    DatabaseFactory.init()
    val kord = Kord(util.Env.DISCORD_TOKEN)

    registerCommands(kord)
    handleCommands(kord)
    startScheduler(kord)

    kord.login {
        presence { playing("tracking Deadlock matches") }
    }
}