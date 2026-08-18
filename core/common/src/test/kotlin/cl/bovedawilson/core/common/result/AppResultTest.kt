package cl.bovedawilson.core.common.result

import org.junit.Assert.assertEquals
import org.junit.Test

class AppResultTest {

    @Test
    fun `map transforma un exito`() {
        val result: AppResult<Int, AppError> = AppResult.Success(2)
        assertEquals(AppResult.Success(4), result.map { it * 2 })
    }

    @Test
    fun `map conserva un fallo sin transformar`() {
        val result: AppResult<Int, AppError> = AppResult.Failure(AppError.InvalidCredentials)
        assertEquals(AppResult.Failure(AppError.InvalidCredentials), result.map { it * 2 })
    }

    @Test
    fun `fold invoca la rama correcta`() {
        val success: AppResult<Int, AppError> = AppResult.Success(1)
        val failure: AppResult<Int, AppError> = AppResult.Failure(AppError.MalformedInput)

        assertEquals("ok:1", success.fold({ "ok:$it" }, { "error" }))
        assertEquals("error", failure.fold({ "ok:$it" }, { "error" }))
    }
}
