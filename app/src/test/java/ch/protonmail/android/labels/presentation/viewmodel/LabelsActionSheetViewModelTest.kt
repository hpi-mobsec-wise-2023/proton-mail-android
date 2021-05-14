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

package ch.protonmail.android.labels.presentation.viewmodel

import android.graphics.Color
import androidx.lifecycle.SavedStateHandle
import ch.protonmail.android.core.Constants
import ch.protonmail.android.core.UserManager
import ch.protonmail.android.data.local.model.Message
import ch.protonmail.android.labels.domain.model.ManageLabelActionResult
import ch.protonmail.android.labels.domain.usecase.GetAllLabels
import ch.protonmail.android.labels.domain.usecase.MoveMessagesToFolder
import ch.protonmail.android.labels.domain.usecase.UpdateLabels
import ch.protonmail.android.labels.presentation.model.ManageLabelItemUiModel
import ch.protonmail.android.labels.presentation.ui.LabelsActionSheet
import ch.protonmail.android.repository.MessageRepository
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runBlockingTest
import me.proton.core.test.android.ArchTest
import me.proton.core.test.kotlin.CoroutinesTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LabelsActionSheetViewModelTest : ArchTest, CoroutinesTest {

    @MockK
    private lateinit var moveMessagesToFolder: MoveMessagesToFolder

    @MockK
    private lateinit var updateLabels: UpdateLabels

    @MockK
    private lateinit var userManager: UserManager

    @MockK
    private lateinit var getAllLabels: GetAllLabels

    @MockK
    private lateinit var savedStateHandle: SavedStateHandle

    @MockK
    private lateinit var messageRepository: MessageRepository

    private lateinit var viewModel: LabelsActionSheetViewModel

    private val messageId1 = "messageId1"
    private val labelId1 = "labelId1"
    private val labelId2 = "labelId2"
    private val iconRes = 123
    private val title = "title"
    private val titleRes = 321
    private val colorInt = Color.YELLOW
    private val message1 = mockk<Message> {
        every { messageId } returns messageId1
        every { labelIDsNotIncludingLocations } returns listOf(labelId1)
    }
    private val model1label = ManageLabelItemUiModel(
        labelId1,
        iconRes,
        title,
        titleRes,
        colorInt,
        true
    )
    private val model2folder = ManageLabelItemUiModel(
        labelId2,
        iconRes,
        title,
        titleRes,
        colorInt,
        false,
        LabelsActionSheet.Type.FOLDER.typeInt
    )

    @BeforeTest
    fun setUp() {
        MockKAnnotations.init(this)

        every { savedStateHandle.get<List<String>>(LabelsActionSheet.EXTRA_ARG_MESSAGES_IDS) } returns listOf(
            messageId1
        )
        every {
            savedStateHandle.get<LabelsActionSheet.Type>(
                LabelsActionSheet.EXTRA_ARG_ACTION_SHEET_TYPE
            )
        } returns LabelsActionSheet.Type.LABEL

        every {
            savedStateHandle.get<Int>(LabelsActionSheet.EXTRA_ARG_CURRENT_FOLDER_LOCATION_ID)
        } returns 0

        coEvery { getAllLabels.invoke(any(), any(), any()) } returns listOf(model1label, model2folder)
        coEvery { messageRepository.findMessageById(messageId1) } returns message1

        viewModel = LabelsActionSheetViewModel(
            savedStateHandle,
            getAllLabels,
            userManager,
            updateLabels,
            moveMessagesToFolder,
            messageRepository
        )
    }

    @Test
    fun verifyThatAfterOnDoneIsClickedLabelsSuccessIsEmitted() = runBlockingTest {

        // given
        val shallMoveToArchive = true
        coEvery { updateLabels.invoke(any(), any()) } just Runs
        coEvery {
            moveMessagesToFolder(
                listOf(messageId1), Constants.MessageLocationType.ARCHIVE.toString(),
                Constants.MessageLocationType.INBOX.messageLocationTypeValue.toString()
            )
        } just Runs

        // when
        viewModel.onDoneClicked(shallMoveToArchive)

        // then
        coVerify { updateLabels.invoke(any(), any()) }
        coVerify {
            moveMessagesToFolder(
                listOf(messageId1), Constants.MessageLocationType.ARCHIVE.toString(),
                Constants.MessageLocationType.INBOX.messageLocationTypeValue.toString()
            )
        }
        assertEquals(ManageLabelActionResult.LabelsSuccessfullySaved, viewModel.actionsResult.value)
    }

    @Test
    fun verifyThatAfterOnLabelIsClickedForLabelType() = runBlockingTest {

        // given
        coEvery { userManager.didReachLabelsThreshold(any()) } returns false

        // when
        viewModel.onLabelClicked(model1label)

        // then
        assertEquals(listOf(model1label.copy(isChecked = false)), viewModel.labels.value)
        assertEquals(ManageLabelActionResult.Default, viewModel.actionsResult.value)
    }

    @Test
    fun verifyThatAfterOnLabelIsClickedForFolderTypeMessagesAreMoved() = runBlockingTest {

        // given
        coEvery { userManager.didReachLabelsThreshold(any()) } returns false
        coEvery { moveMessagesToFolder.invoke(any(), any(), any()) } just Runs

        // when
        viewModel.onLabelClicked(model2folder)

        // then
        coVerify { moveMessagesToFolder.invoke(any(), any(), any()) }
        assertEquals(ManageLabelActionResult.MessageSuccessfullyMoved, viewModel.actionsResult.value)
    }
}