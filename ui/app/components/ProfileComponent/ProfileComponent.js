import React from 'react';
import { Link, withRouter } from 'react-router';

import styles from './ProfileComponent.css';

class ProfileComponent extends React.Component {
  constructor (props) {
    super(props);
    this.state = {
      collapse: true,
      collapseCount: 3,
      filterText: '',
    };
    this.toggle = this.toggle.bind(this);
    this.setFilterText = this.setFilterText.bind(this);
  }

  setFilterText (e) {
    this.setState({ filterText: e.target.value });
  }

  toggle () {
    this.setState({ collapse: !this.state.collapse });
  }

  render () {
    const isCollapsable = this.props.traces.length > this.state.collapseCount;
    const isCollapsed = (isCollapsable && this.state.collapse);

    const tracesScore = Object.keys(this.props.workTypeSummary).reduce((scores, workType) => {
      this.props.workTypeSummary[workType].traces.forEach((trace) => {
        scores[trace.trace_idx] = scores[trace.trace_idx] || 0;
        scores[trace.trace_idx] += trace.props.samples;
      });
      return scores;
    }, []).map((eachScore, i) => ({
      score: eachScore,
      name: this.props.traces[i],
    })).sort((a, b) => b.score - a.score);

    const list = isCollapsed
      ? tracesScore.slice(0, 3) :
        tracesScore.filter(t => t.name.indexOf(this.state.filterText) > -1);

    return (
      <div className={styles['main']}>
        <h4 className={styles.heading}>{this.props.heading}</h4>

        {isCollapsable && !isCollapsed && (
          <div style={{ textAlign: 'center' }}>
            <div className="mdl-textfield mdl-js-textfield mdl-textfield--floating-label">
              <input
                className="mdl-textfield__input"
                type="text"
                value={this.state.filterText}
                placeholder="Type to filter traces"
                onChange={this.setFilterText}
              />
            </div>
          </div>
        )}

        <ol>
          {list && list.map(l => (
            <li key={l.name}>
              <Link to={loc => ({ pathname: `/profiler/profile-data/${l.name}`, query: { ...loc.query, profileStart: this.props.start } })}>
                {l.name}
              </Link>
            </li>
          ))}
        </ol>
        {isCollapsable && (
          <div className={styles.center}>
            <button
              className="mdl-button mdl-js-button mdl-button--accent"
              onClick={this.toggle}
            >
              {this.state.collapse ? 'Show more' : 'Show Less'}
            </button>
          </div>
        )}
      </div>
    );
  }
}

ProfileComponent.propTypes = {
  heading: React.PropTypes.string,
  traces: React.PropTypes.array,
  start: React.PropTypes.string,
  workTypeSummary: React.PropTypes.object,
};

export default withRouter(ProfileComponent);

