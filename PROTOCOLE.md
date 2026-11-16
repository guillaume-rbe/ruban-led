# Protocole Bluetooth LE du ruban Zengge (capture du 2026-11-16)

Capture : journal HCI de l'écran A007, app ZENGGE 1.5.7 (`com.zengge.blev2`), 13:49–13:50.
Fichier brut : `btsnoop_hci.log` (lisible avec Wireshark).

## GATT

| Service | Caractéristique | Handle | Usage |
|---|---|---|---|
| `0xFFFF` | `0xFF01` | 0x0003 | **Commandes** (écriture sans réponse) |
| `0xFFFF` | `0xFF02` | 0x0005 (CCCD 0x0006) | **État** (notifications, activer avec `0100`) |
| `0xFE00` | `0xFF11` / `0xFF22` | 0x0009 / 0x000B | non utilisés par l'app |

## Enveloppe de chaque commande

```
00 SEQ 80 00 00 LEN LEN+1 0B  <commande de LEN octets>
```

- `SEQ` : compteur qui augmente de 1 à chaque envoi (01, 02, 03…).
- `LEN` : longueur de la commande qui suit ; l'octet suivant vaut `LEN+1`.
- Dans les commandes « courtes », le dernier octet est une **somme de contrôle** = somme des octets de la commande, modulo 256.

## Commandes vues

| Action | Commande (après l'enveloppe) |
|---|---|
| Éteindre | `3B 24 00 00 00 00 00 00 00 32 00 00 91` |
| Allumer | `3B 23 00 00 00 00 00 00 00 32 00 00 90` |
| Mettre l'heure | `10 14 AA MM JJ HH MM SS JS 00 0F CS` (AA = année-2000, JS = jour de semaine) |
| Couleur / luminosité (20 segments) | `E1 03 00 14 00 00 14` + 20 fois `A1 T1 T2 V` |
| Effet / scène | `E1 01 00 64 05 00 01 64 50 00 …` (à documenter si besoin) |

Segment `A1 T1 T2 V` (couleur HSV) :
- teinte (0–359) : `T1 = teinte >> 1`, et le bit fort de `T2` = `teinte & 1` ;
- saturation (0–100) : 7 bits bas de `T2` ;
- `V` : luminosité 0–100 (0x64 = 100 %).

Exemples captés : rouge `A1 00 64 64`, vert `A1 36 64 64`, bleu `A1 7C E4 64`, blanc `A1 00 00 64`,
blanc à 75 % `A1 00 00 4B`, 37 % `…25`, 1 % `…01`.

## Notifications d'état (handle 0x0005)

Exemple : `04 0A 80 00 00 1B 1C 16 EA 81 01 00 AD 0A 23 24 01 50 F0 00 00 64 …`
`23` = allumé / `24` = éteint, puis teinte/saturation/luminosité du moment (`00 00 64` = blanc 100 %).

## Pour piloter le ruban

- **Clignoter** (portes ouvertes) : alterner deux commandes couleur, par exemple orange `V=64` puis `V=01`, toutes les 300–500 ms ; ou alterner allumer/éteindre.
- **Baisser le soir** : renvoyer la couleur actuelle avec un `V` plus bas à l'heure voulue.
- Le ruban n'accepte qu'**une connexion à la fois** : l'app de l'iPhone ne doit pas être connectée.
