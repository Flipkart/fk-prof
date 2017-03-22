import React from 'react';
import { Link, withRouter } from 'react-router';

import styles from './ProfileComponent.css';

class ProfileComponent extends React.Component {
  constructor (props) {
    super(props);
    this.state = {
      collapse: true,
      collapseCount: 3,
    };
    this.toggle = this.toggle.bind(this);
  }

  toggle () {
    this.setState({ collapse: !this.state.collapse });
  }

  render () {
    const isCollapsable = this.props.traces.length > this.state.collapseCount;
    const list = (isCollapsable && this.state.collapse)
      ? this.props.traces.slice(0, 3) : this.props.traces;
    return (
      <div className={styles['main']}>
        <h4 className={styles.heading}>{this.props.heading}</h4>
        <ol>
          {list && list.map(l => (
            <li key={l}>
              <Link to={loc => ({ pathname: `/profiler/profile-data/${l}`, query: { ...loc.query, profileStart: this.props.start } })}>
                {l}
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
};

export default withRouter(ProfileComponent);

