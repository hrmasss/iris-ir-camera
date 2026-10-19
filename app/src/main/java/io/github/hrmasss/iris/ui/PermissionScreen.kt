package io.github.hrmasss.iris.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.hrmasss.iris.R

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PermissionScreen(denied: Boolean, onGrant: () -> Unit, onOpenSettings: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.systemBarsPadding().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(112.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(colorResource(R.color.ic_launcher_background)),
            ) {
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.headlineMediumEmphasized, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(if (denied) R.string.permission_denied else R.string.permission_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onGrant) { Text(stringResource(R.string.permission_grant)) }
            if (denied) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.permission_settings)) }
            }
        }
    }
}
