# Nouveautés

Chaque version publiée a ici sa section `## <versionName>` : `release.sh` la reprend comme texte de la release GitHub (et refuse de publier sans elle).

## 0.22

- **Plus de fermeture forcée au redémarrage du service** : le service répond à Android à chaque demande de démarrage (ouverture de l'app, chien de garde, mise à jour), et plus seulement la première fois. Le 23 nov., un redémarrage tombé pendant une de ces demandes avait fait fermer l'app par Android (elle était repartie seule 20 s après).

## 0.21

- **Page mieux répartie** : les cartes « Journaux » et « Derniers événements » passent à gauche, sous « Couleurs », et les deux colonnes finissent à la même hauteur : la page défile beaucoup moins.
- **Boutons alignés** : « Couleur permanente » (sur deux lignes) n'est plus décalé vers le bas, les trois boutons d'action ont la même hauteur ; même chose pour les champs des réglages (« Clignot. (ms) »).
- **Événement « Écran : position » lisible** : il ne contient plus le message d'erreur Android « Broken pipe ».

## 0.20

- **Journal de débogage** : tout ce que l'app voit et fait est gardé sur l'écran (un fichier par jour, 7 au plus : aujourd'hui et les 6 jours d'avant, d'après la date du fichier ; 150 Mo au plus, de quoi garder une semaine avec de longs trajets ; les plus anciens sont supprimés automatiquement, vérifié chaque heure et juste après minuit) : événements, décisions des règles avec leur raison (« Auto Hold : pas d'animation, recharge »…), chaque message CAN avec tous ses champs, chaque commande Bluetooth avec son accusé de réception et son délai, les réponses du ruban, l'état de la liaison, les commandes de l'écran et les plantages.
- **Journal détaillé** (option, activée par défaut) et **codes CAN en plus** (ex. `2,3,4` ou `0-127`) : enregistrer d'autres données de la voiture sans nouvelle version.
- **Bouton « Envoyer les journaux »** (24 h, ou tout) dans la carte « Journaux » : chaque étape s'affiche (✓ / ✗ avec la raison) avec une barre d'avancement. Jusqu'à 20 Mo compressés par envoi (150 à 200 Mo de texte), préparés et envoyés en flux sans tout charger en mémoire. Envoi chiffré avec certificat client vers un serveur personnel, ouvert seulement à la demande ; l'en-tête donne l'état complet de l'app et son logcat.

## 0.19

- **Plus de fenêtre « accès à la position désactivé » au démarrage** (option, activée par défaut) : cette fenêtre de Google (Localiser mon appareil, « Fermer / Activer la localisation ») venait de la Position coupée. À chaque démarrage, Ruban LED met la Position de l'écran en **mode réseau seul, sans GPS** (aucun module n'est branché ; la précision de la localisation Google étant coupée, pas de scans Wi-Fi non plus). Passe par l'ADB réseau de l'écran, comme Binnacle ; l'état avant / après et l'écran au premier plan un peu après le démarrage sont notés dans « Derniers événements ». Désactiver l'option remet le mode d'avant.

## 0.18

- **Plus réactif** : les images d'animation ne s'accumulent plus quand le Bluetooth ralentit (la plus récente remplace celle qui attend), pause entre deux envois réduite (40 → 8 ms, le ruban accuse réception), ruban prêt dès que ses notifications sont activées, et plus d'« allumer » inutile avant le clignotement d'une porte (il part un envoi plus tôt).
- **Connexion au réveil** : quand la voiture se réveille (écran allumé), l'app se connecte au ruban tout de suite au lieu d'attendre le prochain essai (jusqu'à 2,5 s), puis réessaie toutes les 0,5 s pendant 30 s.
- **Commande couleur courte** : une couleur unie part en 13 octets au lieu de 87. Cette commande n'a pas été capturée depuis l'app ZENGGE : l'app la vérifie une fois sur le ruban (« Derniers événements » dit si elle est reconnue) et garde la commande longue sinon.
- Code : une seule vérification « une animation tourne déjà » pour toutes les règles ; changer une couleur ne fait plus clignoter la jauge de recharge.

## 0.17

- **Animation à l'arrêt** (option, activée par défaut) : au feu rouge, quand la voiture tient l'arrêt en Auto Hold depuis plus d'1 s, une fois sur 4 au hasard, une animation au hasard sur le tableau de bord jusqu'au départ : respiration lente, croisement (une lumière part de chaque bout et rejoint l'autre), du centre vers les bouts, scintillement ou vague de couleur.
- **Coffre d'abord en arrivant** : si le coffre (ou le frunk) s'ouvre en premier en arrivant, le ruban clignote en rouge comme d'habitude (l'animation d'allumage s'arrête si elle tournait), puis l'**animation d'allumage** se joue quand une portière s'ouvre, au lieu du clignotement : on la voit en montant dans la voiture.
- **Bleu à l'Autosteer** (option, activée par défaut) : quand l'Autosteer s'active, le ruban passe 1 s dans le bleu de l'Autopilot de Binnacle, à la luminosité du moment, puis revient à la couleur permanente.
- **Blanc en marche arrière** (option, activée par défaut) : en marche arrière, le ruban passe en blanc doux, un peu plus fort que la couleur permanente (+30 points, 50 % la nuit), puis revient à la normale en D ou en P.
- **Limiteur de vitesse, à vérifier** : le champ `speedlimitsw` (sens inconnu) est noté dans « Derniers événements » à chaque changement, pour voir au prochain trajet s'il suit le limiteur.
- Réglage « Arrêt : 1 sur » (4 par défaut, 1 = à chaque arrêt).
- **Boutons de test masqués**, sous le bouton « Boutons de test », et renommés pour être plus clairs : « Animation d'allumage », « Animation d'extinction », « Clignotement d'alerte » et le nouveau « Animations à l'arrêt » (les 5 animations l'une après l'autre, 10 s chacune à chaque appui).

## 0.16

- **Mise à jour sans Internet** : le bouton « Mise à jour » ne reste plus bloqué sur « Téléchargement… ». Sans connexion, il abandonne en quelques secondes (30 s au plus) et affiche « Mise à jour impossible : pas de connexion Internet ».

## 0.15

- **Nouvelle jauge de recharge** : une barre verte fixe au milieu du tableau de bord, aussi large que le pourcentage de batterie (50 % = la moitié du tableau de bord). De chaque bout, un remplissage vert court vers elle, en boucle (~5 s).
- Sur les portes, c'est un remplissage vert de l'arrière vers l'avant qui finit dans la barre (à l'avant de la porte). À 100 %, tout est vert fixe.
- Toujours seulement voiture déverrouillée.

## 0.14

- **Couleurs verrouillées** : les roues de couleur et les jauges de luminosité sont verrouillées (et grisées) par défaut, pour qu'un doigt qui fait défiler la page ne change plus une couleur par erreur (la couleur d'alerte était passée au bleu le 17 nov.).
- Un bouton dans la carte « Couleurs » les déverrouille ; elles se reverrouillent en quittant l'app ou 2 min après le dernier réglage.

## 0.13

- **Musique à la connexion CarPlay** (option, activée par défaut) : quand l'iPhone se connecte et que rien ne joue 3 s plus tard, l'app envoie « lecture » à CarPlay, et l'iPhone relance sa dernière appli audio (YouTube Music…).
- Demande une fois l'accès aux notifications d'Android (bouton dans la carte « Règles »), pris en compte dès qu'il est donné, sans redémarrer l'app.

## 0.12

- **Verrouillage mieux reconnu** : un déverrouillage à moitié vu (un seul des deux flashs) n'est plus pris pour un verrouillage qui éteignait le ruban à l'arrivée.
- Une porte ouverte voiture « verrouillée » compte comme une arrivée, même pendant une animation.

## 0.11

- **Dépôt à part** : l'app a son propre dépôt, `guillaume-rbe/ruban-led`, et y cherche désormais ses mises à jour.
- README complet (captures, explications), documentation des données CAN utilisées, licence GPL-3.0 comme Binnacle.
