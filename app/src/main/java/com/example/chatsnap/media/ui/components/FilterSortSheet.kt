package com.example.chatsnap.media.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatsnap.media.model.FilterOption
import com.example.chatsnap.media.model.SortOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSortBottomSheet(
    currentSort: SortOption,
    currentFilter: FilterOption,
    onSortSelected: (SortOption) -> Unit,
    onFilterSelected: (FilterOption) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E2E),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Sort & Filter",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Filter Section
            Text(
                text = "FILTER MEDIA",
                color = Color(0xFF00D2FF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            FilterOption.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFilterSelected(option) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (option == currentFilter),
                        onClick = { onFilterSelected(option) },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFFFC00))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = option.displayName,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }

            Divider(color = Color.Gray.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 12.dp))

            // Sort Section
            Text(
                text = "SORT BY",
                color = Color(0xFF00D2FF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            SortOption.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSortSelected(option) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (option == currentSort),
                        onClick = { onSortSelected(option) },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFFFC00))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = option.displayName,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
