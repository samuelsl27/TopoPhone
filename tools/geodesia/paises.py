"""Países para la búsqueda de la biblioteca de SC (S034/RF-2, RF-5): código ISO, nombre en español y nombre en inglés tal como lo escribe EPSG.

EPSG no relaciona cada SC con un país: solo da el nombre de su ámbito («France - mainland onshore», «Europe - onshore and offshore: Albania; …»).
`paises_del_ambito` busca en ese nombre el nombre inglés de cada país como palabra completa. Los alias recogen las otras formas que usa EPSG.
"""

from __future__ import annotations

import re

# ISO|español|inglés[|alias inglés separados por «/»]
_TABLA = """
AD|Andorra|Andorra
AE|Emiratos Árabes Unidos|United Arab Emirates/UAE
AF|Afganistán|Afghanistan
AG|Antigua y Barbuda|Antigua and Barbuda
AL|Albania|Albania
AM|Armenia|Armenia
AO|Angola|Angola
AR|Argentina|Argentina
AT|Austria|Austria
AU|Australia|Australia
AZ|Azerbaiyán|Azerbaijan
BA|Bosnia y Herzegovina|Bosnia and Herzegovina
BB|Barbados|Barbados
BD|Bangladés|Bangladesh
BE|Bélgica|Belgium
BF|Burkina Faso|Burkina Faso
BG|Bulgaria|Bulgaria
BH|Baréin|Bahrain
BI|Burundi|Burundi
BJ|Benín|Benin
BO|Bolivia|Bolivia
BR|Brasil|Brazil
BS|Bahamas|Bahamas
BT|Bután|Bhutan
BW|Botsuana|Botswana
BY|Bielorrusia|Belarus
BZ|Belice|Belize
CA|Canadá|Canada
CD|República Democrática del Congo|Congo DR/Democratic Republic of the Congo
CF|República Centroafricana|Central African Republic
CG|Congo|Congo
CH|Suiza|Switzerland
CI|Costa de Marfil|Cote d'Ivoire/Ivory Coast
CL|Chile|Chile
CM|Camerún|Cameroon
CN|China|China
CO|Colombia|Colombia
CR|Costa Rica|Costa Rica
CU|Cuba|Cuba
CV|Cabo Verde|Cape Verde
CY|Chipre|Cyprus
CZ|Chequia|Czechia/Czech Republic
DE|Alemania|Germany
DJ|Yibuti|Djibouti
DK|Dinamarca|Denmark
DM|Dominica|Dominica
DO|República Dominicana|Dominican Republic
DZ|Argelia|Algeria
EC|Ecuador|Ecuador
EE|Estonia|Estonia
EG|Egipto|Egypt
ER|Eritrea|Eritrea
ES|España|Spain
ET|Etiopía|Ethiopia
FI|Finlandia|Finland
FJ|Fiyi|Fiji
FO|Islas Feroe|Faroe Islands/Faroes
FR|Francia|France
GA|Gabón|Gabon
GB|Reino Unido|United Kingdom/UK/Great Britain
GD|Granada|Grenada
GE|Georgia|Georgia
GF|Guayana Francesa|French Guiana
GH|Ghana|Ghana
GL|Groenlandia|Greenland
GM|Gambia|Gambia
GN|Guinea|Guinea
GQ|Guinea Ecuatorial|Equatorial Guinea
GR|Grecia|Greece
GT|Guatemala|Guatemala
GW|Guinea-Bisáu|Guinea-Bissau
GY|Guyana|Guyana
HN|Honduras|Honduras
HR|Croacia|Croatia
HT|Haití|Haiti
HU|Hungría|Hungary
ID|Indonesia|Indonesia
IE|Irlanda|Ireland
IL|Israel|Israel
IN|India|India
IQ|Irak|Iraq
IR|Irán|Iran
IS|Islandia|Iceland
IT|Italia|Italy
JM|Jamaica|Jamaica
JO|Jordania|Jordan
JP|Japón|Japan
KE|Kenia|Kenya
KG|Kirguistán|Kyrgyzstan
KH|Camboya|Cambodia
KR|Corea del Sur|South Korea/Korea
KW|Kuwait|Kuwait
KZ|Kazajistán|Kazakhstan
LA|Laos|Laos
LB|Líbano|Lebanon
LI|Liechtenstein|Liechtenstein
LK|Sri Lanka|Sri Lanka
LR|Liberia|Liberia
LS|Lesoto|Lesotho
LT|Lituania|Lithuania
LU|Luxemburgo|Luxembourg
LV|Letonia|Latvia
LY|Libia|Libya
MA|Marruecos|Morocco
MC|Mónaco|Monaco
MD|Moldavia|Moldova
ME|Montenegro|Montenegro
MG|Madagascar|Madagascar
MK|Macedonia del Norte|North Macedonia/Macedonia
ML|Malí|Mali
MM|Birmania|Myanmar/Burma
MN|Mongolia|Mongolia
MR|Mauritania|Mauritania
MT|Malta|Malta
MU|Mauricio|Mauritius
MV|Maldivas|Maldives
MW|Malaui|Malawi
MX|México|Mexico
MY|Malasia|Malaysia
MZ|Mozambique|Mozambique
NA|Namibia|Namibia
NE|Níger|Niger
NG|Nigeria|Nigeria
NI|Nicaragua|Nicaragua
NL|Países Bajos|Netherlands
NO|Noruega|Norway
NP|Nepal|Nepal
NZ|Nueva Zelanda|New Zealand
OM|Omán|Oman
PA|Panamá|Panama
PE|Perú|Peru
PG|Papúa Nueva Guinea|Papua New Guinea
PH|Filipinas|Philippines
PK|Pakistán|Pakistan
PL|Polonia|Poland
PR|Puerto Rico|Puerto Rico
PT|Portugal|Portugal
PY|Paraguay|Paraguay
QA|Catar|Qatar
RO|Rumanía|Romania
RS|Serbia|Serbia
RU|Rusia|Russia/Russian Federation
RW|Ruanda|Rwanda
SA|Arabia Saudí|Saudi Arabia
SB|Islas Salomón|Solomon Islands
SC|Seychelles|Seychelles
SD|Sudán|Sudan
SE|Suecia|Sweden
SG|Singapur|Singapore
SI|Eslovenia|Slovenia
SK|Eslovaquia|Slovakia
SL|Sierra Leona|Sierra Leone
SM|San Marino|San Marino
SN|Senegal|Senegal
SO|Somalia|Somalia
SR|Surinam|Suriname
SS|Sudán del Sur|South Sudan
SV|El Salvador|El Salvador
SY|Siria|Syria
SZ|Esuatini|Eswatini/Swaziland
TD|Chad|Chad
TG|Togo|Togo
TH|Tailandia|Thailand
TJ|Tayikistán|Tajikistan
TL|Timor Oriental|Timor-Leste/East Timor
TM|Turkmenistán|Turkmenistan
TN|Túnez|Tunisia
TO|Tonga|Tonga
TR|Turquía|Turkey/Türkiye
TT|Trinidad y Tobago|Trinidad and Tobago
TW|Taiwán|Taiwan
TZ|Tanzania|Tanzania
UA|Ucrania|Ukraine
UG|Uganda|Uganda
US|Estados Unidos|United States/USA/US
UY|Uruguay|Uruguay
UZ|Uzbekistán|Uzbekistan
VA|Ciudad del Vaticano|Vatican City/Holy See
VE|Venezuela|Venezuela
VN|Vietnam|Vietnam/Viet Nam
YE|Yemen|Yemen
ZA|Sudáfrica|South Africa
ZM|Zambia|Zambia
ZW|Zimbabue|Zimbabwe
"""


def tabla() -> list[tuple[str, str, str, list[str]]]:
    """(ISO, español, inglés, alias en inglés) ordenados por ISO."""
    filas = []
    for linea in _TABLA.strip().splitlines():
        iso, es, en = linea.split("|")
        nombres = en.split("/")
        filas.append((iso, es, nombres[0], nombres[1:]))
    return sorted(filas)


def _patron(nombre: str) -> str:
    return r"(?<![A-Za-z])" + re.escape(nombre) + r"(?![A-Za-z])"


_NOMBRES = sorted(
    ((nombre, iso) for iso, _es, en, alias in tabla() for nombre in [en, *alias]),
    key=lambda par: (-len(par[0]), par[0]),
)
_PATRONES = [(iso, re.compile(_patron(nombre))) for nombre, iso in _NOMBRES]


def paises_del_ambito(nombre_ambito: str) -> list[str]:
    """Códigos ISO de los países cuyo nombre inglés aparece como palabra completa en el nombre del ámbito de EPSG.

    Los nombres más largos se buscan antes y se tachan, para que «Papua New Guinea» no cuente también como «Guinea».
    """
    texto = nombre_ambito
    encontrados: set[str] = set()
    for iso, patron in _PATRONES:
        if patron.search(texto):
            encontrados.add(iso)
            texto = patron.sub(lambda m: " " * len(m.group(0)), texto)
    return sorted(encontrados)
