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

package nextflow.trace

import java.nio.file.Files
import java.nio.file.Path

import nextflow.processor.TaskHandler
import spock.lang.Specification
import test.TestHelper

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class TimelineObserverTest extends Specification {

    def 'should read html template' () {

        given:
        def observer = [:] as TimelineObserver
        when:
        def tpl = observer.readTemplate()
        then:
        tpl.startsWith '<!doctype html>'
        tpl.contains 'REPLACE_WITH_TIMELINE_DATA'

    }

    def 'should return timeline in json format' () {

        given:
        def now = 1429821425141
        def r1 = new TraceRecord()
        r1.task_id = '1'
        r1.name = 'foo'
        r1.process = 'alpha'

        def r2 = new TraceRecord()
        r2.task_id = '2'
        r2.name = 'bar'
        r2.submit = now
        r2.start = now + 100
        r2.complete = now + 500
        r2.realtime = 400
        r2.duration = 500
        r2.process = 'alpha'

        def r3 = new TraceRecord()
        r3.task_id = '3'
        r3.name = 'baz'
        r3.submit = now
        r3.start = now + 200
        r3.complete = now + 700
        r3.realtime = 500
        r3.duration = 700
        r3.process = 'beta'

        def observer = [:] as TimelineObserver
        observer.startMillis = 1000
        observer.endMillis = 3500
        observer.records['1'] = r1
        observer.records['2'] = r2
        observer.records['3'] = r3

        expect:
        observer.renderData().toString() == /
            var elapsed="2.5s"
            var beginningMillis=1000;
            var endingMillis=3500;
            var data=[
            {"label": "foo", "times": []},
            {"label": "bar", "times": [{"starting_time": 1429821425141, "ending_time": 1429821425241, "color":c1(0)}, {"starting_time": 1429821425241, "ending_time": 1429821425641, "color":c2(0), "label": "500ms \\/ -"}]},
            {"label": "baz", "times": [{"starting_time": 1429821425141, "ending_time": 1429821425341, "color":c1(1)}, {"starting_time": 1429821425341, "ending_time": 1429821425841, "color":c2(1), "label": "700ms \\/ -"}]}
            ]
            /
            .stripIndent().leftTrim()
    }

    def 'should add records' () {

        given:
        def now = 1429821425141
        def r1 = new TraceRecord()
        r1.task_id = '1'
        r1.name = 'foo'
        r1.process = 'alpha'

        def r2 = new TraceRecord()
        r2.task_id = '2'
        r2.name = 'bar'
        r2.submit = now
        r2.start = now + 100
        r2.complete = now + 500
        r2.realtime = 400
        r2.duration = 500
        r2.process = 'alpha'

        def r3 = new TraceRecord()
        r3.task_id = '3'
        r3.name = 'baz'
        r3.submit = now
        r3.start = now + 200
        r3.complete = now + 700
        r3.realtime = 500
        r3.duration = 700
        r3.process = 'beta'

        def h1 = Mock(TaskHandler)
        h1.getTask() >> [id: '1']
        h1.getTraceRecord() >> r1

        def h2 = Mock(TaskHandler)
        h2.getTask() >> [id: '2']
        h2.getTraceRecord() >> r2

        def h3 = Mock(TaskHandler)
        h3.getTask() >> [id: '3']
        h3.getTraceRecord() >> r3

        when:
        def observer = new TimelineObserver(Mock(Path))
        observer.onProcessComplete(h1)
        observer.onProcessComplete(h2)
        observer.onProcessComplete(h3)
        then:
        observer.records['1'] == r1
        observer.records['2'] == r2
        observer.records['3'] == r3

    }

    def 'should create html file' () {

        given:
        def now = 1429821425141
        def r1 = new TraceRecord()
        r1.task_id = '1'
        r1.name = 'foo'
        r1.process = 'alpha'

        def r2 = new TraceRecord()
        r2.task_id = '2'
        r2.name = 'bar'
        r2.submit = now
        r2.start = now + 100
        r2.complete = now + 500
        r2.realtime = 400
        r2.duration = 500
        r2.process = 'alpha'

        def r3 = new TraceRecord()
        r3.task_id = '3'
        r3.name = 'baz'
        r3.submit = now
        r3.start = now + 200
        r3.complete = now + 700
        r3.realtime = 500
        r3.duration = 700
        r3.process = 'beta'

        def file = TestHelper.createInMemTempFile('report.html')
        def observer = new TimelineObserver(file)
        observer.startMillis = 1000
        observer.endMillis = 3500
        observer.records['1'] = r1
        observer.records['2'] = r2
        observer.records['3'] = r3

        when:
        observer.renderHtml()
        then:
        Files.exists(file)
        file.text == '''
<!doctype html>
<!--
  ~ Copyright (c) 2013-2015, Centre for Genomic Regulation (CRG).
  ~ Copyright (c) 2013-2015, Paolo Di Tommaso and the respective authors.
  ~
  ~   This file is part of 'Nextflow'.
  ~
  ~   Nextflow is free software: you can redistribute it and/or modify
  ~   it under the terms of the GNU General Public License as published by
  ~   the Free Software Foundation, either version 3 of the License, or
  ~   (at your option) any later version.
  ~
  ~   Nextflow is distributed in the hope that it will be useful,
  ~   but WITHOUT ANY WARRANTY; without even the implied warranty of
  ~   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  ~   GNU General Public License for more details.
  ~
  ~   You should have received a copy of the GNU General Public License
  ~   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
  -->

<html>
<head>
<script src="http://code.jquery.com/jquery-latest.min.js"></script>
<script src="http://d3js.org/d3.v3.min.js" charset="utf-8"></script>
<script src="http://www.nextflow.io/js/d3-timeline.js"></script>
<link href='http://fonts.googleapis.com/css?family=Lato' rel='stylesheet' type='text/css'/>
<style type="text/css">
* {
  font-family: 'Lato', 'Helvetica Neue', Arial, Helvetica, sans-serif;
}

.axis path,
.axis line {
fill: none;
  stroke: black;
  shape-rendering: crispEdges;
}

.axis text {
  font-size: 10px;
}

div#timeline g text {
  font-size: 13px;
}

text.timeline-label {
  font-size: 13px;
}

#timeline2 .axis {
  transform: translate(0px,30px);
  -ms-transform: translate(0px,30px); /* IE 9 */
  -webkit-transform: translate(0px,30px); /* Safari and Chrome */
  -o-transform: translate(0px,30px); /* Opera */
  -moz-transform: translate(0px,30px); /* Firefox */
}

.coloredDiv {
  height:20px; width:20px; float:left;
}

#footer {
  padding-top: 3em; color: #bfbfbf; font-size: 13px;
}

#footer a:visited {
  color: #bfbfbf;
  text-decoration: underline;
}
</style>
<script type="text/javascript">
var handler=null;
// see https://github.com/mbostock/d3/wiki/Ordinal-Scales#category20c
var colors = d3.scale.category20c().domain(d3.range(0,20)).range()

function c1(index) { return "#bdbdbd"; }
function c2(index) { return colors[index % 16]; } // <-- note: uses only the first 16 colors

var elapsed="2.5s"
var beginningMillis=1000;
var endingMillis=3500;
var data=[
{"label": "foo", "times": []},
{"label": "bar", "times": [{"starting_time": 1429821425141, "ending_time": 1429821425241, "color":c1(0)}, {"starting_time": 1429821425241, "ending_time": 1429821425641, "color":c2(0), "label": "500ms \\/ -"}]},
{"label": "baz", "times": [{"starting_time": 1429821425141, "ending_time": 1429821425341, "color":c1(1)}, {"starting_time": 1429821425341, "ending_time": 1429821425841, "color":c2(1), "label": "700ms \\/ -"}]}
]


function getTickFormat() {
  var MIN = 1000 * 60
  var HOUR = MIN * 60
  var DAY = HOUR * 24
  var delta = (endingMillis - beginningMillis)

  if( delta < 2 * MIN ) {
    return {
      format: d3.time.format("%S"),
      tickTime: d3.time.seconds,
      tickInterval: 5,
      tickSize: 6
    }
  }

  if( delta < 2 * HOUR ) {
    return {
      format: d3.time.format("%M"),
      tickTime: d3.time.minutes,
      tickInterval: 5,
      tickSize: 6
    }
  }

  if( delta < 2 * DAY ) {
    return {
      format: d3.time.format("%H:%M"),
      tickTime: d3.time.hours,
      tickInterval: 1,
      tickSize: 6
    }
  }

  return {
    format: d3.time.format("%b %e %H:%M"),
    tickTime: d3.time.hours,
    tickInterval: 6,
    tickSize: 6
  }
}

function render() {
  handler=null;
  $("#timeline").empty()
  $('#label_elapsed').text(elapsed)
  $('#label_launch').text( d3.time.format('%d %b %Y %H:%M')(new Date(beginningMillis)) )

  var width = $(window).width();
  var chart = d3.timeline()
    .stack() // toggles graph stacking
    .margin({left:150, right:100, top:0, bottom:0})
    .tickFormat( getTickFormat() )
    .rowSeperators('#f5f5f5')
    ;
  var svg = d3.select("#timeline").append("svg").attr("width", width).datum(data).call(chart);
}

function hrz() {
if( handler != null ) clearTimeout(handler)
  handler = setTimeout(render, 150);
}

$(document).ready(render)
$(window).resize(hrz); // resize the applet on window resize
</script>
</head>

<body>
<div>
  <h3>Processes execution timeline</h3>
  <p>
    Launch time: <span id='label_launch'> </span><br>
    Elapsed time: <span id='label_elapsed'> </span>
  </p>
  <div id="timeline"></div>
</div>

<div id='footer'>
  Created with Nextflow -- <a href='http://www.nextflow.io' target='_blank'>http://nextflow.io</a>
</div>
</body>
</html>
        '''.stripIndent().trim()


    }

}
