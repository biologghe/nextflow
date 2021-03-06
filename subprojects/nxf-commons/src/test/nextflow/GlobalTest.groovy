/*
 * Copyright (c) 2013-2015, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2015, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow

import java.nio.file.Files

import spock.lang.Specification
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class GlobalTest extends Specification {


    def testAwsCredentials() {

        expect:
        Global.getAwsCredentials0(null, null) == null
        Global.getAwsCredentials0([AWS_ACCESS_KEY: 'x', AWS_SECRET_KEY: '222'], null) == ['x','222']
        Global.getAwsCredentials0([AWS_ACCESS_KEY_ID: 'q', AWS_SECRET_ACCESS_KEY: '999'], null) == ['q','999']
        Global.getAwsCredentials0([AWS_ACCESS_KEY: 'x', AWS_SECRET_KEY: '222',  AWS_ACCESS_KEY_ID: 'q', AWS_SECRET_ACCESS_KEY: '999'], null) == ['q','999']

        Global.getAwsCredentials0([AWS_ACCESS_KEY_ID: 'q', AWS_SECRET_ACCESS_KEY: '999'], [aws:[accessKey: 'b', secretKey: '333']]) == ['b','333']
        Global.getAwsCredentials0(null, [aws:[accessKey: 'b', secretKey: '333']]) == ['b','333']
        Global.getAwsCredentials0(null, [aws:[accessKey: 'b']]) == null

    }

    def testAwsCredentialsWithFile() {

        given:
        def file = Files.createTempFile('test','test')
        file.text = '''
            [default]
            aws_access_key_id = aaa
            aws_secret_access_key = bbbb
            '''

        Global.getAwsCredentials0(null, null, [file]) == ['aaa','bbbb']
        Global.getAwsCredentials0([AWS_ACCESS_KEY: 'x', AWS_SECRET_KEY: '222'], null, [file]) == ['x','222']

        cleanup:
        file?.delete()

    }


}
