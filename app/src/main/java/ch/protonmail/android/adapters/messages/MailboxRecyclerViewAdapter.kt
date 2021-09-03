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
package ch.protonmail.android.adapters.messages

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import ch.protonmail.android.core.Constants
import ch.protonmail.android.data.local.model.Label
import ch.protonmail.android.data.local.model.PendingSend
import ch.protonmail.android.data.local.model.PendingUpload
import ch.protonmail.android.mailbox.presentation.model.MailboxUiItem
import ch.protonmail.android.utils.ui.selection.SelectionModeEnum
import ch.protonmail.android.views.messagesList.MailboxItemFooterView
import ch.protonmail.android.views.messagesList.MailboxItemView
import kotlinx.android.synthetic.main.layout_sender_initial.view.*
import kotlinx.android.synthetic.main.list_item_mailbox.view.*

class MailboxRecyclerViewAdapter(
    private val context: Context,
    private val onSelectionModeChange: ((SelectionModeEnum) -> Unit)?
) : ListAdapter<MailboxUiItem, MailboxItemViewHolder>(MailboxUiItem.DiffCallback()) {

    private var mailboxLocation = Constants.MessageLocationType.INVALID

    private var labels = mapOf<String, Label>()
    private var mailboxItems = listOf<MailboxUiItem>()
    private val selectedMailboxItemsIds: MutableSet<String> = mutableSetOf()

    private var pendingUploadList: List<PendingUpload>? = null
    private var pendingSendList: List<PendingSend>? = null

    private var onItemClick: ((MailboxUiItem) -> Unit)? = null
    private var onItemSelectionChangedListener: (() -> Unit)? = null

    val checkedMailboxItems get() =
        selectedMailboxItemsIds.mapNotNull { mailboxItems.find { message -> message.itemId == it } }

    public fun getMailboxItem(position: Int) = mailboxItems[position]

    override fun submitList(list: List<MailboxUiItem>?) {
        mailboxItems = list ?: emptyList()
        super.submitList(list)
    }

    override fun submitList(list: List<MailboxUiItem>?, commitCallback: Runnable?) {
        mailboxItems = list ?: emptyList()
        super.submitList(list, commitCallback)
    }

    fun setItemClick(onItemClick: ((MailboxUiItem) -> Unit)?) {
        this.onItemClick = onItemClick
    }

    fun setOnItemSelectionChangedListener(onItemSelectionChangedListener: () -> Unit) {
        this.onItemSelectionChangedListener = onItemSelectionChangedListener
    }

    private enum class ElementType {
        MESSAGE, FOOTER
    }

    override fun getItemViewType(position: Int): Int {
        val itemViewType = if (position == mailboxItems.size) ElementType.FOOTER else ElementType.MESSAGE
        return itemViewType.ordinal
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MailboxItemViewHolder {
        return when (ElementType.values()[viewType]) {
            ElementType.MESSAGE -> MailboxItemViewHolder.MessageViewHolder(MailboxItemView(context))
            ElementType.FOOTER -> MailboxItemViewHolder.FooterViewHolder(MailboxItemFooterView(context))
        }
    }

    override fun onBindViewHolder(holder: MailboxItemViewHolder, position: Int) {
        when (ElementType.values()[getItemViewType(position)]) {
            ElementType.MESSAGE -> (holder as MailboxItemViewHolder.MessageViewHolder).bindMailboxItem(position)
            ElementType.FOOTER -> {
                // NOOP
            }
        }
    }

    private fun selectMessage(messageId: String, position: Int) {
        if (selectedMailboxItemsIds.isEmpty()) {
            onSelectionModeChange?.invoke(SelectionModeEnum.STARTED)
            notifyDataSetChanged()
        }
        selectedMailboxItemsIds.add(messageId)
        onItemSelectionChangedListener?.invoke()
        notifyItemChanged(position)
    }

    private fun deselectMessage(messageId: String, position: Int) {
        selectedMailboxItemsIds.remove(messageId)
        if (selectedMailboxItemsIds.isEmpty()) {
            onSelectionModeChange?.invoke(SelectionModeEnum.ENDED)
            notifyDataSetChanged()
        } else {
            onItemSelectionChangedListener?.invoke()
            notifyItemChanged(position)
        }
    }

    private fun selectOrDeselectMessage(messageId: String, position: Int): Boolean {
        if (onSelectionModeChange == null || onItemSelectionChangedListener == null) {
            return false
        }

        if (selectedMailboxItemsIds.contains(messageId)) {
            deselectMessage(messageId, position)
        } else {
            selectMessage(messageId, position)
        }
        return true
    }

    private fun MailboxItemViewHolder.MessageViewHolder.bindMailboxItem(position: Int) {
        val mailboxItem = mailboxItems[position]

        val pendingSend = pendingSendList?.find { it.messageId == mailboxItem.itemId }
        val isBeingSent = pendingSend != null && pendingSend.sent == null
        val isAttachmentsBeingUploaded = pendingUploadList?.find { it.messageId == mailboxItem.itemId } != null

        this.view.bind(
            mailboxItem,
            selectedMailboxItemsIds.isNotEmpty(),
            mailboxLocation,
            isBeingSent,
            isAttachmentsBeingUploaded
        )

        val isSelected = selectedMailboxItemsIds.contains(mailboxItem.itemId)
        this.view.isActivated = isSelected
        this.view.tag = mailboxItem.itemId
        this.view.sender_initial_view.tag = mailboxItem.itemId

        this.view.sender_initial_view.setOnClickListener {
            val messageId = it.tag as String
            selectOrDeselectMessage(messageId, position)
        }

        this.view.setOnClickListener {
            if (selectedMailboxItemsIds.isNotEmpty()) {
                val messageId = it.tag as String
                selectOrDeselectMessage(messageId, position)
            } else {
                onItemClick?.invoke(mailboxItem)
            }
        }
        this.view.setOnLongClickListener {
            if (selectedMailboxItemsIds.isEmpty()) {
                val messageId = it.tag as String
                return@setOnLongClickListener selectOrDeselectMessage(messageId, position)
            }
            return@setOnLongClickListener true
        }
    }

    fun endSelectionMode() {
        selectedMailboxItemsIds.clear()
        notifyDataSetChanged()
    }

    fun setLabels(labels: List<Label>) {
        this.labels = labels.map { it.id to it }.toMap()
        notifyDataSetChanged()
    }

    fun setPendingUploadsList(pendingUploadList: List<PendingUpload>) {
        this.pendingUploadList = pendingUploadList
        notifyDataSetChanged()
    }

    fun setPendingForSendingList(pendingSendList: List<PendingSend>) {
        this.pendingSendList = pendingSendList
        notifyDataSetChanged()
    }

    fun setNewLocation(locationType: Constants.MessageLocationType) {
        mailboxLocation = locationType
    }

    /**
     * @return `true` if any items withing the given positions' range has an [MailboxUiItem.itemId] that matches one
     *  from [mailboxItemsIds]
     */
    fun isAnyMailboxItemWithinPositions(
        mailboxItemsIds: List<String>,
        startPosition: Int,
        endPosition: Int
    ) = mailboxItems.subList(startPosition, endPosition + 1)
        .any { it.itemId in mailboxItemsIds }

}
