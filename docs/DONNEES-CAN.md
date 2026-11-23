# Données de la voiture utilisées (relevés du 16 et 17 novembre 2026)

Tout est lu **en lecture seule** auprès de l'app CAN d'origine de l'écran (`com.xbs.canapp`) :
abonnement (`regPropIdList`) aux codes 15, 9, 10 et 11, désabonnement (`unregPropIdList`), rien d'autre.
Rien n'est envoyé à la voiture ni au MCU. Les relevés ont été faits avec l'enregistreur (« logger »)
de [Binnacle](https://github.com/lotech/binnacle), qui écrit tous les codes 0 à 127.

## Champs lus

| Code | Champ | Valeurs | Usage |
|---|---|---|---|
| 15 | `doorval` | bits : `0x80` conducteur, `0x40` passager, `0x20` arrière droite, `0x10` arrière gauche, `0x08` coffre, `0x04` frunk | clignotement rouge à chaque ouverture |
| 9 | `leftlight` / `rightlight` | 1 = allumé, 2 = éteint (suivent le clignotement réel) | verrouillage, déverrouillage, warnings |
| 9 | `postlight` | `true` / `false` | feux d'accueil : signe le déverrouillage |
| 9 | `chargelight` | `true` pendant la charge | jauge de batterie |
| 9 | `autodrivermode` | `true` pendant l'Autosteer (vérifié par Binnacle le 28 oct.) | 1 s de bleu à l'activation |
| 10 | `carspeed` | km/h | arrêt en D après avoir roulé = Auto Hold (animation à l'arrêt) |
| 11 | `gear` | 0 = P, 1 = R, 3 = D (vérifié par Binnacle) | blanc en marche arrière |
| 11 | `jhmail`, `batterylevel` | voir `Battery.java` (table de Binnacle) | pourcentage de batterie |

## Verrouillage et déverrouillage (17 nov.)

La Tesla confirme le verrouillage par les clignotants, et ça passe dans le flux CAN.

Départ à pied, verrouillage automatique en s'éloignant, puis retour :

| Heure | Événement | Données |
|---|---|---|
| 10:37:47 | porte ouverte pour sortir | `doorval=64` |
| 10:37:51 | conducteur hors du siège | `safeline=false` |
| 10:37:54 | porte fermée | `doorval=0` |
| **10:38:04** | **verrouillage auto** (≈ 10 s après la porte) | **un flash** : `leftlight`/`rightlight` 1 puis 2 |
| **10:38:35.2** | **retour, déverrouillage** | **`postlight=true`** |
| 10:38:35.3 | porte ouverte (70 ms après) | `doorval=64` |
| **10:38:35.4–36.2** | confirmation du déverrouillage | **deux flashs** |
| 10:38:40 | porte fermée | `postlight=false` |

Même motif en verrouillant puis déverrouillant depuis l'écran de la Tesla (10:35:16 un flash ;
10:35:37 `postlight=true` et deux flashs). Les warnings donnent des flashs continus (environ toutes
les 0,7 s), d'où la règle : 1 flash = verrouillé, 2 = déverrouillé, 3 ou plus = warnings.
Il faut que la confirmation lumineuse du verrouillage soit activée dans les réglages de la Tesla.

## Ce qui n'est pas dans le flux

- **État verrouillé en tant que tel** : pas de champ dédié, seulement les flashs de confirmation.
- **Auto Hold** : sur cette Model 3, `autohandlight` (code 9) est en fait le **frein de parking** :
  `true` en P (au démarrage, après s'être garé), `false` dès le passage en D, et `false` à chaque
  Auto Hold au feu (journal du 23 nov.). L'écran du kit l'affiche pourtant en « H ». Pas lu par l'app.
- **Limiteur de vitesse et régulateur seul (TACC)** : aucun champ des codes 9, 10, 11, 15 ne bouge
  (essai du 23 nov., 16:21). `speedlimitsw` (code 10) est l'affichage du panneau de vitesse
  (`speedlimit` = 30, 50, 70…) : `true` dès qu'on roule, `false` en P.
- Feux stop, autonomie réelle (sur cette voiture) : voir la carte des champs de Binnacle.

## Câblage du ruban

Le contrôleur Zengge a trois sorties qui reçoivent **le même signal** (20 segments d'environ
7,5 cm) : les deux portes (75 cm) affichent les segments 1 à 10 depuis l'arrière, le tableau de bord
(110 cm) les segments 1 à 15 depuis la droite. Les portes ne sont pas séparables entre elles.
