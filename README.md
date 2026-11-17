# Ruban LED

Petite app Android pour l'écran ZIQI A007 (Tesla Model 3/Y) qui pilote en Bluetooth LE un ruban LED
Zengge (app « ZENGGE », `com.zengge.blev2`) selon l'état des portes de la voiture.

Séparée de Binnacle : elle ne lit que les portes, les feux et la batterie, **en lecture seule**, auprès
de l'app CAN d'origine (`com.xbs.canapp`, codes 15, 9 et 11, seules transactions `regPropIdList`/`unregPropIdList`, comme le
`CanFeed` de Binnacle). Rien n'est envoyé à la voiture ni au MCU.

## Ce qu'elle fait

- **Verrouillage = présence** : l'app lit la confirmation lumineuse de la Tesla sur les clignotants
  (code 9). **Un flash** des deux clignotants = voiture verrouillée (le propriétaire part) :
  animation d'extinction **3 fois**, puis ruban éteint. **Deux flashs**, ou les feux d'accueil
  (`postlight`) qui s'allument = déverrouillée (il arrive) : animation d'allumage **3 fois** dans la
  couleur permanente, puis couleur permanente. Verrouillée, rien ne s'allume (ni warnings, ni jauge
  de recharge), pour ne pas gaspiller d'énergie. Une porte qui s'ouvre voiture « verrouillée » vaut
  déverrouillage (il a été manqué). Déverrouillée, une porte qui s'ouvre fait clignoter le ruban.
  Relevé le 17 nov. : verrouillage auto en s'éloignant ≈ 10 s après la fermeture de la porte ;
  au retour, feux d'accueil puis deux flashs au moment où la porte s'ouvre. Il faut la
  confirmation lumineuse du verrouillage activée dans les réglages Tesla.
- **Animations** : uniquement au verrouillage et au déverrouillage détectés (jamais sur une porte).
  L'allumage remplit le ruban progressivement (~5 s, **3 fois**) dans la couleur permanente
  (ambiante) : les portes de l'arrière vers l'avant, le tableau de bord depuis les deux bouts.
  L'extinction fait l'inverse. Interrupteur « Animations ×3 » : sans lui, allumé / éteint d'un coup.
- **Clignotement** : chaque porte, coffre ou frunk qui s'ouvre (même si une autre est déjà
  ouverte, et même conducteur à bord) fait clignoter le ruban (couleur d'alerte, rouge par défaut,
  100 % ↔ 1 %, 400 ms, 2 min max).
- **Après chaque animation** : toujours la couleur permanente (la jauge pendant la recharge), ou
  ruban éteint si la voiture est verrouillée.
- **Warnings** : à partir de **3 flashs** de suite des deux clignotants (1 ou 2, c'est le
  verrouillage), et tant qu'ils continuent, le ruban clignote dans la couleur d'alerte.
- **Recharge** : pendant la charge (code 9 `chargelight`), et seulement voiture déverrouillée, le ruban
  devient une jauge verte du pourcentage de batterie (code 11, décodé comme Binnacle), remplie
  depuis la droite du tableau de bord, son extrémité pulsant.
- **Veille** : quand l'écran s'éteint (voiture endormie), le ruban s'éteint, sans animation
  (en général il l'est déjà depuis le verrouillage). Utile car il est alimenté par
  l'USB de la boîte à gants, qui reste alimenté tant que la voiture est éveillée (Sentinelle
  comprise).
- **Mode nuit**, activé par défaut : de 19 h (heure d'hiver) ou 21 h (heure d'été) à 7 h, tout le
  ruban (couleur permanente, animations, jauge) à 20 %. Heures et luminosité réglables.
- **Deux couleurs** réglables dans l'app, chacune avec sa roue et sa jauge de luminosité :
  couleur permanente, et couleur d'alerte (rouge 100 % par défaut, réservée aux portes, coffre, frunk et warnings).
- Écran sombre en cartes : état, présence, couleurs, derniers événements (heure de chaque porte,
  connexion du ruban… pour voir d'où vient un retard), actions, règles, ruban et mise à jour.

## Rétroviseurs

Abandonnés en 0.7 : l'app CAN d'origine ne publie pas leur état (enregistrement de tous les codes
0–127 avec le logger de Binnacle le 17 nov., rétros repliés puis dépliés plusieurs fois : aucun champ
n'a changé). Le verrouillage, lu sur les clignotants, les remplace.

## Priorité et réactivité

- Les règles tournent sur leur propre fil de priorité haute (plus sur le fil de l'écran), le fil
  Bluetooth aussi ; la connexion BLE est en intervalle court et se refait toutes les 2,5 s.
- Service au premier plan (notification « Ruban LED actif »), relancé au démarrage, après une mise à
  jour et toutes les 15 min s'il a été arrêté ; le processeur reste éveillé pendant les animations.
- Au premier lancement, Android demande de laisser l'app hors de l'optimisation de la batterie :
  accepter. Sur l'A007, ajouter aussi `app.rubanled` dans `/data/system/appPowerSaveConfig.xml`
  (voir plus bas).

## Câblage constaté

Le contrôleur a trois sorties qui reçoivent **le même signal** (20 segments d'environ 7,5 cm) :
les deux portes (75 cm) affichent les segments 1 à 10 depuis l'arrière, le tableau de bord (110 cm)
les segments 1 à 15 depuis la droite. Les deux portes ne peuvent donc pas être pilotées séparément.

## Protocole

Voir [PROTOCOLE.md](PROTOCOLE.md) (capturé avec le journal HCI Android pendant l'utilisation de
l'app ZENGGE). Points à retenir :

- service `FFFF`, écriture `FF01` **avec réponse** (propriétés 0x0C) : en écriture sans réponse, le
  ruban perd des commandes quand elles s'enchaînent ;
- les notifications d'état (`FF02`) décrivent la commande **précédente** : l'app retient ce qu'elle
  envoie et ignore les réponses qui arrivent moins de 1,5 s après un envoi ;
- « allumer » envoyé juste après une couleur remet du blanc : allumer d'abord, couleur ensuite.

## Compiler et installer

Outils du SDK Android seulement (pas de Gradle) : build-tools 36, plateforme android-35, le JDK
d'Android Studio, Python.

```bash
bash build.sh
adb install -r build/ruban-led.apk
```

Au premier lancement, saisir l'adresse Bluetooth du ruban dans l'app (elle n'est pas dans le code)
puis « Enregistrer ». Sur l'A007, ajouter `app.rubanled` dans `/data/system/appPowerSaveConfig.xml`
(même ligne que `com.shilapi.xcertplay`, `autolaunch="2" secondarylaunch="2"`) pour qu'elle démarre
avec l'écran.

Signée avec la clé de debug du PC (`~/.android/debug.keystore`). Garder toujours la même clé :
sinon Android refuse la mise à jour.

## Mises à jour depuis l'app

Le bouton « Mise à jour » cherche dans les releases de `guillaume-rbe/binnacle` la plus récente
taguée `ruban-led-v<versionCode>`, plus récente que la version installée. Il télécharge
`ruban-led.apk`, vérifie son SHA-256 (`ruban-led.apk.sha256`), puis l'installe avec
l'installateur d'Android, verrouillé sur ce paquet : Android affiche sa propre confirmation et
n'accepte qu'une APK signée avec la même clé. La première fois, autoriser « Installer des
applis inconnues » pour Ruban LED (l'app ouvre l'écran d'Android).

Si Android refuse la connexion de l'app (« socket failed: EPERM », vu sur l'A007 quand la
permission Internet est arrivée par une mise à jour : Android la donne mais le processus ne l'a
pas), l'app passe par le gestionnaire de téléchargements d'Android, qui télécharge à sa place.
Si cela échoue aussi : `adb uninstall app.rubanled` puis `adb install ruban-led.apk` (les réglages
sont à refaire).

Publier une version : augmenter `android:versionCode` dans `AndroidManifest.xml`, pousser la
branche `ruban-led`, puis `bash release.sh` (crée la release avec `gh`, sans la marquer « latest »).
