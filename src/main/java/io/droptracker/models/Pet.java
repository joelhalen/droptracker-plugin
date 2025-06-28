/*
 * Copyright (c) 2021, Illya Myshakov <https://github.com/IllyaMyshakov>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package io.droptracker.models;

import lombok.AllArgsConstructor;
import lombok.Getter;
import net.runelite.api.ItemID;

@AllArgsConstructor
@Getter
public enum Pet
{
    @SuppressWarnings("deprecation")
    ABYSSAL_ORPHAN("Abyssal orphan", ItemID.ABYSSAL_ORPHAN),
    @SuppressWarnings("deprecation")
    IKKLE_HYDRA("Ikkle hydra", ItemID.IKKLE_HYDRA),
    @SuppressWarnings("deprecation")
    CALLISTO_CUB("Callisto cub", ItemID.CALLISTO_CUB),
    @SuppressWarnings("deprecation")
    HELLPUPPY("Hellpuppy", ItemID.HELLPUPPY),
    @SuppressWarnings("deprecation")
    PET_CHAOS_ELEMENTAL("Pet chaos elemental", ItemID.PET_CHAOS_ELEMENTAL),
    @SuppressWarnings("deprecation")
    PET_ZILYANA("Pet zilyana", ItemID.PET_ZILYANA),
    @SuppressWarnings("deprecation")
    PET_DARK_CORE("Pet dark core", ItemID.PET_DARK_CORE),
    @SuppressWarnings("deprecation")
    PET_DAGANNOTH_PRIME("Pet dagannoth prime", ItemID.PET_DAGANNOTH_PRIME),
    @SuppressWarnings("deprecation")
    PET_DAGANNOTH_SUPREME("Pet dagannoth supreme", ItemID.PET_DAGANNOTH_SUPREME),
    @SuppressWarnings("deprecation")
    PET_DAGANNOTH_REX("Pet dagannoth rex", ItemID.PET_DAGANNOTH_REX),
    @SuppressWarnings("deprecation")
    TZREKJAD("Tzrek-jad", ItemID.TZREKJAD),
    @SuppressWarnings("deprecation")
    PET_GENERAL_GRAARDOR("Pet general graardor", ItemID.PET_GENERAL_GRAARDOR),
    @SuppressWarnings("deprecation")
    BABY_MOLE("Baby mole", ItemID.BABY_MOLE),
    @SuppressWarnings("deprecation")
    NOON("Noon", ItemID.NOON),
    @SuppressWarnings("deprecation")
    JALNIBREK("Jal-nib-rek", ItemID.JALNIBREK),
    @SuppressWarnings("deprecation")
    KALPHITE_PRINCESS("Kalphite princess", ItemID.KALPHITE_PRINCESS),
    @SuppressWarnings("deprecation")
    PRINCE_BLACK_DRAGON("Prince black dragon", ItemID.PRINCE_BLACK_DRAGON),
    @SuppressWarnings("deprecation")
    PET_KRAKEN("Pet kraken", ItemID.PET_KRAKEN),
    @SuppressWarnings("deprecation")
    PET_KREEARRA("Pet kree'arra", ItemID.PET_KREEARRA),
    @SuppressWarnings("deprecation")
    PET_KRIL_TSUTSAROTH("Pet k'ril tsutsaroth", ItemID.PET_KRIL_TSUTSAROTH),
    @SuppressWarnings("deprecation")
    SCORPIAS_OFFSPRING("Scorpia's offspring", ItemID.SCORPIAS_OFFSPRING),
    @SuppressWarnings("deprecation")
    SKOTOS("Skotos", ItemID.SKOTOS),
    @SuppressWarnings("deprecation")
    PET_SMOKE_DEVIL("Pet smoke devil", ItemID.PET_SMOKE_DEVIL),
    @SuppressWarnings("deprecation")
    VENENATIS_SPIDERLING("Venenatis spiderling", ItemID.VENENATIS_SPIDERLING),
    @SuppressWarnings("deprecation")
    VETION_JR("Vet'ion jr.", ItemID.VETION_JR),
    @SuppressWarnings("deprecation")
    VORKI("Vorki", ItemID.VORKI),
    @SuppressWarnings("deprecation")
    PHOENIX("Phoenix", ItemID.PHOENIX),
    @SuppressWarnings("deprecation")
    PET_SNAKELING("Pet snakeling", ItemID.PET_SNAKELING),
    @SuppressWarnings("deprecation")
    OLMLET("Olmlet", ItemID.OLMLET),
    @SuppressWarnings("deprecation")
    LIL_ZIK("Lil' zik", ItemID.LIL_ZIK),
    @SuppressWarnings("deprecation")
    BLOODHOUND("Bloodhound", ItemID.BLOODHOUND),
    @SuppressWarnings("deprecation")
    PET_PENANCE_QUEEN("Pet penance queen", ItemID.PET_PENANCE_QUEEN),
    @SuppressWarnings("deprecation")
    HERON("Heron", ItemID.HERON),
    @SuppressWarnings("deprecation")
    ROCK_GOLEM("Rock golem", ItemID.ROCK_GOLEM),
    @SuppressWarnings("deprecation")
    BEAVER("Beaver", ItemID.BEAVER),
    @SuppressWarnings("deprecation")
    BABY_CHINCHOMPA("Baby chinchompa", ItemID.BABY_CHINCHOMPA_13324),
    @SuppressWarnings("deprecation")
    GIANT_SQUIRREL("Giant squirrel", ItemID.GIANT_SQUIRREL),
    @SuppressWarnings("deprecation")
    TANGLEROOT("Tangleroot", ItemID.TANGLEROOT),
    @SuppressWarnings("deprecation")
    ROCKY("Rocky", ItemID.ROCKY),
    @SuppressWarnings("deprecation")
    RIFT_GUARDIAN("Rift guardian", ItemID.RIFT_GUARDIAN),
    @SuppressWarnings("deprecation")
    HERBI("Herbi", ItemID.HERBI),
    @SuppressWarnings("deprecation")
    CHOMPY_CHICK("Chompy chick", ItemID.CHOMPY_CHICK),
    @SuppressWarnings("deprecation")
    SRARACHA("Sraracha", ItemID.SRARACHA),
    @SuppressWarnings("deprecation")
    SMOLCANO("Smolcano", ItemID.SMOLCANO),
    @SuppressWarnings("deprecation")
    YOUNGLLEF("Youngllef", ItemID.YOUNGLLEF),
    @SuppressWarnings("deprecation")
    LITTLE_NIGHTMARE("Little nightmare", ItemID.LITTLE_NIGHTMARE),
    @SuppressWarnings("deprecation")
    LIL_CREATOR("Lil' creator", ItemID.LIL_CREATOR),
    @SuppressWarnings("deprecation")
    TINY_TEMPOR("Tiny tempor", ItemID.TINY_TEMPOR),
    @SuppressWarnings("deprecation")
    NEXLING("Nexling", ItemID.NEXLING),
    @SuppressWarnings("deprecation")
    ABYSSAL_PROTECTOR("Abyssal protector", ItemID.ABYSSAL_PROTECTOR),
    ;

    private final String name;
    private final Integer iconID;

    static Pet findPet(String petName)
    {
        for (Pet pet : values())
        {
            if (pet.name.equals(petName))
            {
                return pet;
            }
        }
        return null;
    }
}