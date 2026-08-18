package cl.bovedawilson.core.common.result

/**
 * Resultado de una operación que puede fallar con un error tipado. Usado por las
 * operaciones de alto nivel de `:core:crypto` (`AppResult<T, CryptoError>`) y, a partir de
 * la Fase 5, por los repositorios de `:data:sync` una vez traducido el error a [AppError]
 * (`docs/architecture.md` §2).
 */
sealed class AppResult<out T, out E> {
    data class Success<out T>(val value: T) : AppResult<T, Nothing>()
    data class Failure<out E>(val error: E) : AppResult<Nothing, E>()

    inline fun <R> map(transform: (T) -> R): AppResult<R, E> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun <R> fold(onSuccess: (T) -> R, onFailure: (E) -> R): R = when (this) {
        is Success -> onSuccess(value)
        is Failure -> onFailure(error)
    }
}
