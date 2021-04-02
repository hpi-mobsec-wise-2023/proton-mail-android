/*
 * Copyright (c) 2020 Proton Technologies AG
 *
 * This file is part of ProtonMail.
 *
 * ProtonMail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonMail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonMail. If not, see https://www.gnu.org/licenses/.
 */

package ch.protonmail.android.mailbox.domain

import ch.protonmail.android.core.Constants.MessageLocationType
import ch.protonmail.android.domain.entity.Id
import ch.protonmail.android.mailbox.domain.model.Parameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.proton.core.domain.arch.DataResult
import javax.inject.Inject

class GetConversations @Inject constructor(
    private val conversationRepository: ConversationsRepository
) {

    suspend operator fun invoke(userId: Id, location: MessageLocationType): Flow<GetConversationsResult> {
        val params = Parameters.GetConversationsParameters(userId = userId.s, location)
        return conversationRepository.getConversations(params, true)
            .map {
                when (it) {
                    is DataResult.Success -> return@map GetConversationsResult.Success(it.value as List<Conversation>)
                    else -> return@map GetConversationsResult.Error
                }
            }
    }
}
